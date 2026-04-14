// SPDX-License-Identifier: Apache-2.0

package org.hiero.mirror.common.domain.contract;

import static org.assertj.core.api.Assertions.assertThat;

import org.hiero.mirror.common.domain.entity.EntityId;
import org.hiero.mirror.common.util.CommonUtils;
import org.junit.jupiter.api.Test;

final class ContractResultTest {

    @Test
    void setBloom() {
        final var contractResult = new ContractResult();

        // when, then
        final byte[] bloom = CommonUtils.nextBytes(256);
        contractResult.setBloom(bloom);
        assertThat(contractResult.getBloom()).isEqualTo(bloom);

        // when, then
        contractResult.setBloom(new byte[256]);
        assertThat(contractResult.getBloom()).isEmpty();
    }

    @Test
    void toContractTransactionHash() {
        final var transactionHash = CommonUtils.nextBytes(32);
        final var contractResult = ContractResult.builder()
                .consensusTimestamp(1L)
                .contractId(2L)
                .payerAccountId(EntityId.of(3L))
                .transactionHash(transactionHash)
                .transactionResult(4)
                .build();

        assertThat(contractResult.toContractTransactionHash())
                .usingRecursiveComparison()
                .isEqualTo(ContractTransactionHash.builder()
                        .consensusTimestamp(1L)
                        .entityId(2L)
                        .hash(transactionHash)
                        .payerAccountId(3L)
                        .transactionResult(4)
                        .build());
    }

    @Test
    void toContractTransactionHashSkipsEmptyContractId() {
        final var contractResult = ContractResult.builder()
                .contractId(EntityId.EMPTY.getId())
                .payerAccountId(EntityId.of(3L))
                .transactionHash(CommonUtils.nextBytes(32))
                .transactionResult(4)
                .build();

        assertThat(contractResult.toContractTransactionHash()).isNull();
    }
}
