// SPDX-License-Identifier: Apache-2.0

package org.hiero.mirror.importer.parser.record.transactionhandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import com.google.protobuf.Int64Value;
import com.hederahashgraph.api.proto.java.EthereumTransactionBody;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import java.util.concurrent.atomic.AtomicLong;
import org.hiero.mirror.common.domain.entity.EntityId;
import org.hiero.mirror.common.domain.entity.EntityType;
import org.hiero.mirror.importer.parser.record.ethereum.EthereumTransactionParser;
import org.hiero.mirror.importer.parser.record.ethereum.EthereumTransactionSenderRecovery;
import org.hiero.mirror.importer.service.ContractBytecodeService;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/**
 * Regression fixture for the importer {@code signerNonce + 1} mirror-AHEAD-by-1 drift fix.
 *
 * <p>Parent issues:
 * {@code ~/goliath/mainnet/docs/issues/2026-05-21-relay-wrong-nonce-mirror-ahead-of-consensus-divergence.md}
 * and {@code ~/goliath/mainnet/docs/issues/2026-05-29-relay-nonce-too-high-mirror-ahead-consensus-recurrence.md}.
 *
 * <h2>Why the drift existed</h2>
 *
 * Goliath consensus node (live JAR {@code git.commit.id=77738c87b455887682ea5f4e1992d102898b02e2})
 * populates {@code ContractFunctionResult.signer_nonce} from the sender account's nonce
 * <em>read AFTER</em> EVM execution:
 *
 * <pre>
 *   ContextTransactionProcessor.java:195 (success path)
 *       var result = processor.processTransaction(...);   // CustomGasCharging.java:127 -> sender.incrementNonce()
 *       result = result.withSignerNonce(sender.getNonce()); // sender already at n+1
 * </pre>
 *
 * So for a SUCCESS Ethereum transaction submitted at tx-body nonce {@code n}:
 * <ul>
 *   <li>consensus validates {@code transaction.nonce() == sender.getNonce()} -> sender was at {@code n}</li>
 *   <li>{@code CustomGasCharging.incrementNonce()} bumps sender to {@code n + 1}</li>
 *   <li>{@code withSignerNonce(sender.getNonce())} therefore records {@code signerNonce = n + 1}</li>
 *   <li>consensus authoritative {@code AccountInfo.ethereumNonce = n + 1}</li>
 * </ul>
 *
 * The importer ({@code EthereumTransactionHandler.updateAccountNonce}) previously computed
 * {@code nonce = functionResult.getSignerNonce().getValue() + 1} for a success CFR. That yielded
 * {@code (n + 1) + 1 = n + 2}, while consensus is at {@code n + 1} — mirror AHEAD by 1. The relay then
 * served that drifted value (or rejected too-high submits), burning the FRA operator wrapper fee.
 *
 * <h2>The fix this test pins</h2>
 *
 * The importer now writes {@code nonce = functionResult.getSignerNonce().getValue()} for a success CFR,
 * because {@code signer_nonce} is already the post-transaction nonce. Mirror {@code entity.ethereum_nonce}
 * therefore equals consensus {@code AccountInfo.ethereumNonce} — drift 0.
 *
 * <h2>Live mainnet evidence (captured 2026-05-21 / 2026-05-29)</h2>
 *
 * <ul>
 *   <li>Account {@code 0.0.1010} (EVM {@code 0xaa91057c8f98af30c44bb8708399bf4daa188a81})</li>
 *   <li>Pre-fix: consensus {@code AccountInfoQuery(0.0.1010).ethereumNonce} was exactly 1 below mirror
 *       REST {@code ethereum_nonce} after each SUCCESS EthereumTransaction (e.g. 170 vs 171, then 171 vs 172).</li>
 * </ul>
 *
 * <p>This test FAILS on the old {@code signerNonce + 1} code (drift 1) and PASSES on the fix (drift 0).
 * {@code MIRROR_AHEAD_DRIFT} is the single knob: if a future change re-introduces the over-increment,
 * this assertion turns red.
 */
final class EthereumTransactionHandlerSignerNonceDriftTest extends AbstractTransactionHandlerTest {

    /**
     * Consensus's authoritative post-transaction sender nonce for a SUCCESS EthereumTransaction.
     * On the live consensus JAR this is {@code signerNonce}, captured AFTER EVM execution has
     * already incremented the sender account nonce. It equals {@code AccountInfo.ethereumNonce}.
     */
    private static final long CONSENSUS_AUTHORITATIVE_NONCE = 170L;

    /**
     * The signer_nonce value the consensus node writes into the success CFR. Identical to
     * {@link #CONSENSUS_AUTHORITATIVE_NONCE} because consensus reads the sender nonce post-increment.
     */
    private static final long CFR_SIGNER_NONCE = CONSENSUS_AUTHORITATIVE_NONCE;

    /**
     * What the fixed importer writes to {@code entity.ethereum_nonce} for a success CFR: the
     * signer_nonce value directly (no {@code +1}), because it is already the post-tx nonce.
     */
    private static final long IMPORTER_WRITTEN_NONCE = CFR_SIGNER_NONCE;

    /**
     * The expected drift after the fix: mirror equals consensus. This constant is the single knob
     * that turns red if the {@code signerNonce + 1} over-increment is ever re-introduced.
     */
    private static final long MIRROR_AHEAD_DRIFT = 0L;

    @Mock
    private ContractBytecodeService contractBytecodeService;

    @Mock(strictness = LENIENT)
    private EthereumTransactionParser ethereumTransactionParser;

    @Mock
    private EthereumTransactionSenderRecovery senderRecovery;

    @Override
    protected TransactionHandler getTransactionHandler() {
        doReturn(domainBuilder.ethereumTransaction(false).get())
                .when(ethereumTransactionParser)
                .decode(any());
        return new EthereumTransactionHandler(
                contractBytecodeService, entityListener, entityProperties, ethereumTransactionParser, senderRecovery);
    }

    @Override
    protected TransactionBody.Builder getDefaultTransactionBody() {
        return TransactionBody.newBuilder()
                .setEthereumTransaction(EthereumTransactionBody.newBuilder().build());
    }

    @Override
    protected TransactionRecord.Builder getDefaultTransactionRecord() {
        return TransactionRecord.newBuilder()
                .setConsensusTimestamp(MODIFIED_TIMESTAMP)
                .setReceipt(getTransactionReceipt(ResponseCodeEnum.SUCCESS))
                .setContractCallResult(recordItemBuilder.contractFunctionResult(contractId));
    }

    @Override
    protected EntityType getExpectedEntityIdType() {
        return EntityType.CONTRACT;
    }

    /**
     * The EthereumTransactionHandler intentionally persists data for unsuccessful transactions, so the
     * generic {@code AbstractTransactionHandlerTest.updateTransactionUnsuccessful()} contract does not
     * apply. The canonical {@code EthereumTransactionHandlerTest} disables it for the same reason.
     */
    @SuppressWarnings("java:S2699")
    @org.junit.jupiter.api.Disabled("EthereumTransactionHandler persists data for unsuccessful transactions")
    @Override
    @Test
    void updateTransactionUnsuccessful() {
        throw new UnsupportedOperationException("Not applicable to EthereumTransactionHandler");
    }

    /**
     * The fix proof.
     *
     * <p>Builds a SUCCESS EthereumTransaction record with a ContractCallResult whose
     * {@code signer_nonce} is the consensus-authoritative post-tx nonce (exactly what the live
     * consensus node produces), runs {@code EthereumTransactionHandler.updateTransaction} in
     * isolation, captures what the handler writes to {@code entity.ethereum_nonce}, and proves it
     * equals consensus (drift 0). This FAILS on the old {@code signerNonce + 1} code.
     */
    @Test
    void successCfrWithSignerNonceMatchesConsensusNonceNoDrift() {
        // given — a SUCCESS EthereumTransaction whose CFR carries the consensus post-tx signerNonce
        var ethereumTransaction = domainBuilder.ethereumTransaction(false).get();
        doReturn(ethereumTransaction).when(ethereumTransactionParser).decode(any());

        var recordItem = recordItemBuilder
                .ethereumTransaction(false)
                .record(r -> r.getContractCallResultBuilder().setSignerNonce(Int64Value.of(CFR_SIGNER_NONCE)))
                .build();
        var transaction = domainBuilder
                .transaction()
                .customize(t -> t.consensusTimestamp(recordItem.getConsensusTimestamp()))
                .get();

        var functionResult = recordItem.getTransactionRecord().getContractCallResult();
        var senderId = EntityId.of(functionResult.getSenderId());
        var capturedNonce = new AtomicLong(Long.MIN_VALUE);

        // when — run the importer nonce path in isolation
        transactionHandler.updateTransaction(transaction, recordItem);

        // then — capture what the importer wrote to entity.ethereum_nonce
        verify(entityListener).onEntity(org.mockito.ArgumentMatchers.argThat(e -> {
            if (e.getId() == senderId.getId() && e.getEthereumNonce() != null) {
                capturedNonce.set(e.getEthereumNonce());
                return true;
            }
            return false;
        }));

        long importerWrote = capturedNonce.get();

        // 1) The CFR signer_nonce field carries the consensus-authoritative post-tx nonce.
        assertThat(functionResult.getSignerNonce().getValue())
                .as("consensus CFR signer_nonce == AccountInfo.ethereumNonce (post-EVM-increment)")
                .isEqualTo(CONSENSUS_AUTHORITATIVE_NONCE);

        // 2) The fixed importer writes signer_nonce DIRECTLY (no +1) for a success CFR
        //    (EthereumTransactionHandler.updateAccountNonce). FAILS on the old `+1` code.
        assertThat(importerWrote)
                .as("fixed importer writes signer_nonce directly (no +1) for a success CFR")
                .isEqualTo(IMPORTER_WRITTEN_NONCE);

        // 3) NO DRIFT: mirror entity.ethereum_nonce equals consensus AccountInfo.ethereumNonce.
        //    If the `signerNonce + 1` over-increment is ever re-introduced this assertion turns red.
        assertThat(importerWrote - CONSENSUS_AUTHORITATIVE_NONCE)
                .as("mirror entity.ethereum_nonce equals consensus (drift 0) after the signerNonce fix — "
                        + "guards against re-introducing the mirror-AHEAD-by-1 over-increment "
                        + "(docs/issues/2026-05-21 & 2026-05-29).")
                .isEqualTo(MIRROR_AHEAD_DRIFT);
    }

    /**
     * Control case: WRONG_NONCE success-CFR shape must NOT advance the nonce at all.
     *
     * <p>This is the importer's existing guard ({@code EthereumTransactionHandler}, the WRONG_NONCE
     * early return inside the {@code hasSignerNonce()} branch). WRONG_NONCE does not consume a nonce
     * at consensus, so the importer must leave {@code entity.ethereum_nonce} untouched. This guard is
     * orthogonal to the {@code +1} fix and must keep passing.
     */
    @Test
    void wrongNonceCfrDoesNotAdvanceNonce() {
        var ethereumTransaction = domainBuilder.ethereumTransaction(false).get();
        doReturn(ethereumTransaction).when(ethereumTransactionParser).decode(any());

        var recordItem = recordItemBuilder
                .ethereumTransaction(false)
                .status(ResponseCodeEnum.WRONG_NONCE)
                .record(r -> r.getContractCallResultBuilder().setSignerNonce(Int64Value.of(CFR_SIGNER_NONCE)))
                .build();
        var transaction = domainBuilder
                .transaction()
                .customize(t -> t.consensusTimestamp(recordItem.getConsensusTimestamp()))
                .get();

        var functionResult = recordItem.getTransactionRecord().getContractCallResult();
        var senderId = EntityId.of(functionResult.getSenderId());

        transactionHandler.updateTransaction(transaction, recordItem);

        // The importer must NOT emit any entity nonce update for a WRONG_NONCE CFR.
        verify(entityListener, org.mockito.Mockito.never())
                .onEntity(org.mockito.ArgumentMatchers.argThat(
                        e -> e.getId() == senderId.getId() && e.getEthereumNonce() != null));
    }
}
