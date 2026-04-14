// SPDX-License-Identifier: Apache-2.0

import {ContractService} from '../../service';
import {TransactionResult} from '../../model';
import {assertSqlQueryEqual} from '../testutils';

describe('ContractService.ethereumTransactionsByHashQuery', () => {
  test('prefers successful, latest, and non-empty entity ids', () => {
    const successTransactionResult = TransactionResult.getProtoId('SUCCESS');
    const contractServiceQuery = ContractService.constructor.ethereumTransactionsByHashQuery;

    const expected = `
      select * from contract_transaction_hash
      where hash = $1
      order by (transaction_result = ${successTransactionResult}) desc,
               consensus_timestamp desc,
               (entity_id <> 0) desc,
               entity_id desc
      limit 1`;

    assertSqlQueryEqual(contractServiceQuery, expected);
  });
});
