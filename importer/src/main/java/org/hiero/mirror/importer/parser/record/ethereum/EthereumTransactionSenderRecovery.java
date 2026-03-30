// SPDX-License-Identifier: Apache-2.0

package org.hiero.mirror.importer.parser.record.ethereum;

import static org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1.CONTEXT;
import static org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1.SECP256K1_EC_UNCOMPRESSED;

import com.esaulpaugh.headlong.rlp.RLPEncoder;
import com.esaulpaugh.headlong.util.Integers;
import jakarta.inject.Named;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.hiero.mirror.common.domain.entity.EntityId;
import org.hiero.mirror.common.domain.transaction.EthereumTransaction;
import org.hiero.mirror.importer.repository.EntityRepository;
import org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recovers the sender's EVM address and entity ID from an {@link EthereumTransaction} by performing ECDSA public key
 * recovery (ecrecover) on the transaction's signature fields.
 */
@Named
@RequiredArgsConstructor
public class EthereumTransactionSenderRecovery {

    private static final Logger log = LoggerFactory.getLogger(EthereumTransactionSenderRecovery.class);
    private static final int EVM_ADDRESS_LENGTH = 20;
    private static final int SIGNATURE_COMPONENT_LENGTH = 32;
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    private final EntityRepository entityRepository;

    /**
     * Recovers the sender EntityId from an EthereumTransaction's signature. Returns {@link EntityId#EMPTY} if recovery
     * fails or the entity is not found in the database.
     */
    public EntityId recoverSender(EthereumTransaction ethereumTransaction) {
        var evmAddress = recoverEvmAddress(ethereumTransaction);
        if (evmAddress == null) {
            return EntityId.EMPTY;
        }
        return entityRepository.findByEvmAddress(evmAddress).map(EntityId::of).orElse(EntityId.EMPTY);
    }

    /**
     * Recovers the 20-byte EVM address from the transaction signature. Returns null if recovery fails.
     */
    public byte[] recoverEvmAddress(EthereumTransaction ethereumTransaction) {
        try {
            var signingHash = computeSigningHash(ethereumTransaction);
            return ecrecover(
                    signingHash,
                    ethereumTransaction.getSignatureR(),
                    ethereumTransaction.getSignatureS(),
                    ethereumTransaction.getRecoveryId());
        } catch (Exception e) {
            log.warn("Failed to recover EVM address from ethereum transaction: {}", e.getMessage());
            return null;
        }
    }

    private byte[] computeSigningHash(EthereumTransaction tx) {
        var unsignedBytes = encodeUnsignedTransaction(tx);
        return new Keccak.Digest256().digest(unsignedBytes);
    }

    private byte[] encodeUnsignedTransaction(EthereumTransaction tx) {
        return switch (tx.getType()) {
            case 0 -> encodeLegacyUnsigned(tx);
            case 1 -> encodeEip2930Unsigned(tx);
            case 2 -> encodeEip1559Unsigned(tx);
            default -> throw new IllegalArgumentException("Unsupported transaction type: " + tx.getType());
        };
    }

    /**
     * Legacy (type=0) EIP-155 signing hash: rlp([nonce, gasPrice, gasLimit, to, value, data, chainId, 0, 0]). Pre
     * EIP-155 (no chainId): rlp([nonce, gasPrice, gasLimit, to, value, data]).
     */
    private byte[] encodeLegacyUnsigned(EthereumTransaction tx) {
        if (tx.getChainId() != null) {
            // EIP-155: append chainId, empty, empty in place of v, r, s
            return RLPEncoder.list(
                    Integers.toBytes(tx.getNonce()),
                    tx.getGasPrice(),
                    Integers.toBytes(tx.getGasLimit()),
                    tx.getToAddress(),
                    getValueForRlp(tx),
                    tx.getCallData(),
                    tx.getChainId(),
                    EMPTY_BYTE_ARRAY,
                    EMPTY_BYTE_ARRAY);
        }
        // Pre-EIP-155: no chain ID fields
        return RLPEncoder.list(
                Integers.toBytes(tx.getNonce()),
                tx.getGasPrice(),
                Integers.toBytes(tx.getGasLimit()),
                tx.getToAddress(),
                getValueForRlp(tx),
                tx.getCallData());
    }

    /**
     * EIP-2930 (type=1) signing hash: keccak256(0x01 || rlp([chainId, nonce, gasPrice, gasLimit, to, value, data,
     * accessList])).
     */
    private byte[] encodeEip2930Unsigned(EthereumTransaction tx) {
        return RLPEncoder.sequence(
                Integers.toBytes(Eip2930EthereumTransactionParser.EIP2930_TYPE_BYTE),
                List.of(
                        tx.getChainId(),
                        Integers.toBytes(tx.getNonce()),
                        tx.getGasPrice(),
                        Integers.toBytes(tx.getGasLimit()),
                        tx.getToAddress(),
                        getValueForRlp(tx),
                        tx.getCallData(),
                        emptyAccessList()));
    }

    /**
     * EIP-1559 (type=2) signing hash: keccak256(0x02 || rlp([chainId, nonce, maxPriorityFeePerGas, maxFeePerGas,
     * gasLimit, to, value, data, accessList])).
     */
    private byte[] encodeEip1559Unsigned(EthereumTransaction tx) {
        return RLPEncoder.sequence(
                Integers.toBytes(Eip1559EthereumTransactionParser.EIP1559_TYPE_BYTE),
                List.of(
                        tx.getChainId(),
                        Integers.toBytes(tx.getNonce()),
                        tx.getMaxPriorityFeePerGas(),
                        tx.getMaxFeePerGas(),
                        Integers.toBytes(tx.getGasLimit()),
                        tx.getToAddress(),
                        getValueForRlp(tx),
                        tx.getCallData(),
                        emptyAccessList()));
    }

    /**
     * Converts the transaction value for RLP encoding. BigInteger(0) is stored as byte[]{0} in the domain object, but
     * RLP expects an empty array for zero values. This matches
     * {@link AbstractEthereumTransactionParser#getValue(EthereumTransaction)}.
     */
    private static byte[] getValueForRlp(EthereumTransaction tx) {
        return Integers.toBytesUnsigned(new BigInteger(tx.getValue()));
    }

    /**
     * Returns an empty access list for unsigned transaction encoding. This matches how the parsers encode access lists
     * in their encode() methods.
     */
    private static List<?> emptyAccessList() {
        return List.of();
    }

    /**
     * Performs ECDSA public key recovery and derives the 20-byte EVM address. This follows the same pattern as
     * {@code Utility.recoverAddressFromPubKey(secp256k1_pubkey)}.
     */
    @SuppressWarnings("java:S1191")
    private static byte[] ecrecover(byte[] hash, byte[] signatureR, byte[] signatureS, int recoveryId) {
        // Build 64-byte compact signature (r || s), padding each component to 32 bytes
        var paddedR = padLeft(signatureR, SIGNATURE_COMPONENT_LENGTH);
        var paddedS = padLeft(signatureS, SIGNATURE_COMPONENT_LENGTH);
        var compactSig = new byte[64];
        System.arraycopy(paddedR, 0, compactSig, 0, SIGNATURE_COMPONENT_LENGTH);
        System.arraycopy(paddedS, 0, compactSig, SIGNATURE_COMPONENT_LENGTH, SIGNATURE_COMPONENT_LENGTH);

        // Parse the recoverable signature
        var sig = new LibSecp256k1.secp256k1_ecdsa_recoverable_signature();
        if (LibSecp256k1.secp256k1_ecdsa_recoverable_signature_parse_compact(CONTEXT, sig, compactSig, recoveryId)
                != 1) {
            log.warn("Failed to parse compact signature for ecrecover");
            return null;
        }

        // Recover the public key
        var pubKey = new LibSecp256k1.secp256k1_pubkey();
        if (LibSecp256k1.secp256k1_ecdsa_recover(CONTEXT, pubKey, sig, hash) != 1) {
            log.warn("Failed to recover public key from signature");
            return null;
        }

        // Serialize uncompressed public key (65 bytes: 0x04 prefix + 64 bytes)
        var recoveredFullKey = ByteBuffer.allocate(65);
        var fullKeySize = new com.sun.jna.ptr.LongByReference(recoveredFullKey.limit());
        LibSecp256k1.secp256k1_ec_pubkey_serialize(
                CONTEXT, recoveredFullKey, fullKeySize, pubKey, SECP256K1_EC_UNCOMPRESSED);

        // Skip the 0x04 prefix byte, hash the remaining 64 bytes, take last 20 as address
        recoveredFullKey.get(); // discard prefix
        var pubKeyBytes = new byte[64];
        recoveredFullKey.get(pubKeyBytes, 0, 64);

        var keyHash = new Keccak.Digest256().digest(pubKeyBytes);
        var address = new byte[EVM_ADDRESS_LENGTH];
        System.arraycopy(keyHash, keyHash.length - EVM_ADDRESS_LENGTH, address, 0, EVM_ADDRESS_LENGTH);
        return address;
    }

    /**
     * Left-pads a byte array with zeros to the specified length. If the input is already the target length or longer, it
     * is returned as-is.
     */
    private static byte[] padLeft(byte[] input, int length) {
        if (input == null || input.length == 0) {
            return new byte[length];
        }
        if (input.length >= length) {
            return input;
        }
        var padded = new byte[length];
        System.arraycopy(input, 0, padded, length - input.length, input.length);
        return padded;
    }
}
