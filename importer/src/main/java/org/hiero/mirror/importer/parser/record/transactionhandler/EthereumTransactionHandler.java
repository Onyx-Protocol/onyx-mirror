// SPDX-License-Identifier: Apache-2.0

package org.hiero.mirror.importer.parser.record.transactionhandler;

import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import jakarta.inject.Named;
import java.math.BigInteger;
import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.ArrayUtils;
import org.hiero.mirror.common.converter.WeiBarTinyBarConverter;
import org.hiero.mirror.common.domain.contract.ContractResult;
import org.hiero.mirror.common.domain.entity.EntityId;
import org.hiero.mirror.common.domain.transaction.EthereumTransaction;
import org.hiero.mirror.common.domain.transaction.RecordFile;
import org.hiero.mirror.common.domain.transaction.RecordItem;
import org.hiero.mirror.common.domain.transaction.Transaction;
import org.hiero.mirror.common.domain.transaction.TransactionType;
import org.hiero.mirror.common.util.DomainUtils;
import org.hiero.mirror.importer.parser.record.entity.EntityListener;
import org.hiero.mirror.importer.parser.record.entity.EntityProperties;
import org.hiero.mirror.importer.parser.record.ethereum.EthereumTransactionParser;
import org.hiero.mirror.importer.parser.record.ethereum.EthereumTransactionSenderRecovery;
import org.hiero.mirror.importer.service.ContractBytecodeService;
import org.hiero.mirror.importer.util.Utility;

@CustomLog
@Named
@RequiredArgsConstructor
final class EthereumTransactionHandler extends AbstractTransactionHandler {

    private final ContractBytecodeService contractBytecodeService;
    private final EntityListener entityListener;
    private final EntityProperties entityProperties;
    private final EthereumTransactionParser ethereumTransactionParser;
    private final EthereumTransactionSenderRecovery senderRecovery;

    /**
     * Attempts to extract the contract ID from the ethereumTransaction.
     *
     * @param recordItem to check
     * @return The contract ID associated with this ethereum transaction call
     */
    @Override
    public EntityId getEntity(RecordItem recordItem) {
        var transactionRecord = recordItem.getTransactionRecord();

        // pull entity from ContractResult
        var contractFunctionResult = transactionRecord.hasContractCreateResult()
                ? transactionRecord.getContractCreateResult()
                : transactionRecord.getContractCallResult();

        return EntityId.of(contractFunctionResult.getContractID());
    }

    @Override
    public TransactionType getType() {
        return TransactionType.ETHEREUMTRANSACTION;
    }

    @Override
    public void updateContractResult(ContractResult contractResult, RecordItem recordItem) {
        if (!recordItem.isBlockstream()) {
            super.updateContractResult(contractResult, recordItem);
            return;
        }

        if (recordItem.getEthereumTransaction() == null) {
            // This can happen when decoding from the transaction bytes has failed, set default values for not-null
            // columns
            contractResult.setFunctionParameters(ArrayUtils.EMPTY_BYTE_ARRAY);
            contractResult.setGasLimit(0L);
            return;
        }

        // In blockstreams, no EvmTransactionResult.internal_call_context is populated for ethereum transactions.
        // The values for the fields amount / gasLimit / functionParameters should get populated from the transaction
        // body and the call data file if offloaded.
        var ethereumTransaction = recordItem.getEthereumTransaction();
        contractResult.setAmount(new BigInteger(ethereumTransaction.getValue()).longValue());
        contractResult.setGasLimit(ethereumTransaction.getGasLimit());

        byte[] callData = ethereumTransaction.getCallData();
        var callDataId = ethereumTransaction.getCallDataId();
        if (ArrayUtils.isEmpty(callData) && !EntityId.isEmpty(callDataId)) {
            // call data file (callDataId) is ignored by consensus node if there's call data in ethereum data
            callData = contractBytecodeService.get(callDataId);
            if (callData == null) {
                Utility.handleRecoverableError(
                        "Failed to read call data from file {} for ethereum transaction at {}",
                        callDataId,
                        recordItem.getConsensusTimestamp());
            }
        }

        // #12199, function_parameters is a not-null db column, so set it to an empty array as fallback
        contractResult.setFunctionParameters(callData != null ? callData : ArrayUtils.EMPTY_BYTE_ARRAY);
    }

    @Override
    protected void doUpdateTransaction(Transaction transaction, RecordItem recordItem) {
        if (!entityProperties.getPersist().isEthereumTransactions()) {
            return;
        }

        var body = recordItem.getTransactionBody().getEthereumTransaction();
        var ethereumDataBytes = DomainUtils.toBytes(body.getEthereumData());
        try {
            var ethereumTransaction = ethereumTransactionParser.decode(ethereumDataBytes);

            // update ethereumTransaction with body values
            if (body.hasCallData()) {
                ethereumTransaction.setCallDataId(EntityId.of(body.getCallData()));
            }

            // update ethereumTransaction with record values
            var transactionRecord = recordItem.getTransactionRecord();
            ethereumTransaction.setConsensusTimestamp(recordItem.getConsensusTimestamp());
            ethereumTransaction.setData(ethereumDataBytes);
            ethereumTransaction.setHash(DomainUtils.toBytes(transactionRecord.getEthereumHash()));
            ethereumTransaction.setMaxGasAllowance(body.getMaxGasAllowance());
            ethereumTransaction.setPayerAccountId(recordItem.getPayerAccountId());

            if (ArrayUtils.isEmpty(ethereumTransaction.getHash())) {
                var hash = ethereumTransactionParser.getHash(
                        ethereumTransaction.getCallData(),
                        ethereumTransaction.getCallDataId(),
                        ethereumTransaction.getConsensusTimestamp(),
                        ethereumTransaction.getData(),
                        true);
                ethereumTransaction.setHash(hash);
            }

            // EVM logic uses weibar for gas values, convert transaction body gas values to tinybars
            convertGasWeiToTinyBars(ethereumTransaction);

            entityListener.onEthereumTransaction(ethereumTransaction);
            updateAccountNonce(recordItem, ethereumTransaction);
            recordItem.setEthereumTransaction(ethereumTransaction);

            recordItem.addEntityId(ethereumTransaction.getCallDataId());
        } catch (RuntimeException e) {
            Utility.handleRecoverableError(
                    "Error decoding Ethereum transaction data at {}", recordItem.getConsensusTimestamp(), e);
        }
    }

    private void updateAccountNonce(RecordItem recordItem, EthereumTransaction ethereumTransaction) {
        if (!entityProperties.getPersist().isTrackNonce()) {
            return;
        }

        var transactionRecord = recordItem.getTransactionRecord();
        if (!transactionRecord.hasContractCallResult() && !transactionRecord.hasContractCreateResult()) {
            // No CFR present. Some statuses still consume nonce at consensus.
            var status = transactionRecord.getReceipt().getStatus();
            if (status == ResponseCodeEnum.WRONG_NONCE || status == ResponseCodeEnum.DUPLICATE_TRANSACTION) {
                return;
            }
            if (!recordItem.isSuccessful()) {
                advanceNonceFromTxBody(recordItem, ethereumTransaction);
            }
            return;
        }

        var functionResult = transactionRecord.hasContractCreateResult()
                ? transactionRecord.getContractCreateResult()
                : transactionRecord.getContractCallResult();
        var senderId = EntityId.of(functionResult.getSenderId());
        if (EntityId.isEmpty(senderId)) {
            return;
        }

        Long nonce = null;
        if (functionResult.hasSignerNonce()) {
            // Goliath divergence: WRONG_NONCE does not consume a nonce — skip to prevent regression
            var status = transactionRecord.getReceipt().getStatus();
            if (status == ResponseCodeEnum.WRONG_NONCE) {
                return;
            }
            // Goliath divergence: signerNonce is the consumed nonce; +1 aligns with next-nonce semantics
            nonce = functionResult.getSignerNonce().getValue() + 1;
        } else if (recordItem.getHapiVersion().isLessThan(RecordFile.HAPI_VERSION_0_47_0)) {
            var status = transactionRecord.getReceipt().getStatus();
            if (!recordItem.isSuccessful()
                    && status != ResponseCodeEnum.CONTRACT_REVERT_EXECUTED
                    && status != ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED) {
                return;
            }

            // Increment the nonce for backwards compatibility
            nonce = ethereumTransaction.getNonce() + 1;
        }

        if (nonce != null) {
            var entity = senderId.toEntity();
            entity.setEthereumNonce(nonce);
            entity.setTimestampRange(null); // Don't trigger a history row
            entityListener.onEntity(entity);
            recordItem.addEntityId(senderId);
        }
    }

    private void advanceNonceFromTxBody(RecordItem recordItem, EthereumTransaction ethereumTransaction) {
        Long nonce = ethereumTransaction.getNonce();
        if (nonce == null) {
            return;
        }
        long nextNonce = nonce + 1;

        EntityId senderId = senderRecovery.recoverSender(ethereumTransaction);
        if (EntityId.isEmpty(senderId)) {
            log.warn(
                    "Could not recover sender for EthereumTransaction at {} with status {}. "
                            + "Nonce advancement skipped.",
                    recordItem.getConsensusTimestamp(),
                    recordItem.getTransactionRecord().getReceipt().getStatus());
            return;
        }

        var entity = senderId.toEntity();
        entity.setEthereumNonce(nextNonce);
        entity.setTimestampRange(null);
        entityListener.onEntity(entity);
        recordItem.addEntityId(senderId);
    }

    private void convertGasWeiToTinyBars(EthereumTransaction transaction) {
        var converter = WeiBarTinyBarConverter.INSTANCE;
        transaction.setGasPrice(converter.convert(transaction.getGasPrice(), false));
        transaction.setMaxFeePerGas(converter.convert(transaction.getMaxFeePerGas(), false));
        transaction.setMaxPriorityFeePerGas(converter.convert(transaction.getMaxPriorityFeePerGas(), false));
        transaction.setValue(converter.convert(transaction.getValue(), true));
    }
}
