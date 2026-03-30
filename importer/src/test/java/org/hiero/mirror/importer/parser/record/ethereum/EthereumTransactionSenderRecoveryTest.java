// SPDX-License-Identifier: Apache-2.0

package org.hiero.mirror.importer.parser.record.ethereum;

import static org.assertj.core.api.Assertions.assertThat;

import org.hiero.mirror.common.domain.entity.EntityId;
import org.hiero.mirror.common.domain.transaction.EthereumTransaction;
import org.hiero.mirror.importer.repository.EntityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EthereumTransactionSenderRecoveryTest {

    @Mock
    private EntityRepository entityRepository;

    @InjectMocks
    private EthereumTransactionSenderRecovery senderRecovery;

    @Test
    void recoverEvmAddress_nullSignatureR_returnsNull() {
        var tx = EthereumTransaction.builder()
                .type(2)
                .nonce(0L)
                .chainId(new byte[] {1})
                .gasLimit(21000L)
                .toAddress(new byte[20])
                .value(new byte[] {0})
                .callData(new byte[0])
                .maxPriorityFeePerGas(new byte[] {1})
                .maxFeePerGas(new byte[] {1})
                .signatureR(null)
                .signatureS(new byte[32])
                .recoveryId(0)
                .build();
        assertThat(senderRecovery.recoverEvmAddress(tx)).isNull();
    }

    @Test
    void recoverEvmAddress_nullSignatureS_returnsNull() {
        var tx = EthereumTransaction.builder()
                .type(2)
                .nonce(0L)
                .chainId(new byte[] {1})
                .gasLimit(21000L)
                .toAddress(new byte[20])
                .value(new byte[] {0})
                .callData(new byte[0])
                .maxPriorityFeePerGas(new byte[] {1})
                .maxFeePerGas(new byte[] {1})
                .signatureR(new byte[32])
                .signatureS(null)
                .recoveryId(0)
                .build();
        assertThat(senderRecovery.recoverEvmAddress(tx)).isNull();
    }

    @Test
    void recoverSender_invalidSignature_returnsEmpty() {
        // Dummy all-zero signatures fail ecrecover gracefully, returning EMPTY without hitting entity lookup
        var tx = EthereumTransaction.builder()
                .type(2)
                .nonce(0L)
                .chainId(new byte[] {1})
                .gasLimit(21000L)
                .toAddress(new byte[20])
                .value(new byte[] {0})
                .callData(new byte[0])
                .maxPriorityFeePerGas(new byte[] {1})
                .maxFeePerGas(new byte[] {1})
                .signatureR(new byte[32])
                .signatureS(new byte[32])
                .recoveryId(0)
                .build();

        var result = senderRecovery.recoverSender(tx);
        assertThat(result).isEqualTo(EntityId.EMPTY);
    }

    @Test
    void recoverEvmAddress_invalidSignature_returnsNull() {
        // All-zero R/S with valid structure should fail ecrecover, not throw
        var tx = EthereumTransaction.builder()
                .type(2)
                .nonce(0L)
                .chainId(new byte[] {1})
                .gasLimit(21000L)
                .toAddress(new byte[20])
                .value(new byte[] {0})
                .callData(new byte[0])
                .maxPriorityFeePerGas(new byte[] {1})
                .maxFeePerGas(new byte[] {1})
                .signatureR(new byte[32])
                .signatureS(new byte[32])
                .recoveryId(0)
                .build();

        assertThat(senderRecovery.recoverEvmAddress(tx)).isNull();
    }
}
