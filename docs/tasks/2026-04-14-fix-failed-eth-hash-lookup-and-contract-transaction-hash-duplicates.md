# Fix Failed Ethereum Hash Lookup and contract_transaction_hash Duplicates

**Project:** Goliath Mirror Node (`mirror-node`)
**Type:** Code Bug | Data Integrity | REST
**Priority:** P1
**Risk level:** High
**Requires deployment?:** Yes
**Requires network freeze?:** No
**Owner:** Goliath Engineering
**Date created:** 2026-04-14
**Related docs / prior issues:** `~/goliath/mainnet/docs/issues/2026-04-14-blockscout-pending-tx-loop-failed-eth-hash-receipt-race.md`, `~/goliath/mainnet/docs/issues/2026-03-31-contract-result-contract-id-zero-rest-api-404-relay-record-unavailable.md`

---

## 1) GOAL / SUCCESS CRITERIA

- [ ] New failed no-CFR Ethereum transactions do not emit importer-side `contract_transaction_hash` rows with `entity_id = 0`
- [ ] Existing duplicate hash rows resolve deterministically through the REST hash lookup path
- [ ] `/api/v1/contracts/results/<hash>` returns 200 for the known reproducer hash after deployment

---

## 2) IMPLEMENTED CODE CHANGES

### Files changed

- `common/src/main/java/org/hiero/mirror/common/domain/contract/ContractResult.java`
- `importer/src/main/java/org/hiero/mirror/importer/parser/record/entity/sql/SqlEntityListener.java`
- `rest/service/contractService.js`
- `common/src/test/java/org/hiero/mirror/common/domain/contract/ContractResultTest.java`
- `importer/src/test/java/org/hiero/mirror/importer/parser/record/entity/sql/SqlEntityListenerTest.java`
- `rest/__tests__/service/contractService.query.test.js`
- `rest/__tests__/integrationDomainOps.js`
- `rest/__tests__/service/contractService.test.js`

### Behavior change

1. `ContractResult.toContractTransactionHash()` now returns `null` when `contract_id` is empty/zero.
2. `SqlEntityListener.onContractResult()` adds the hash row only when that conversion returns a real object.
3. The REST hash lookup query now prefers:
   - successful rows first
   - latest timestamp next
   - non-empty `entity_id` rows last as tie-breaker

This leaves existing trigger-written corrected rows readable immediately, while stopping the importer from producing fresh zero-entity duplicates.

---

## 3) LOCAL VERIFICATION

### Passed locally

- `JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :common:test --tests 'org.hiero.mirror.common.domain.contract.ContractResultTest' :importer:compileTestJava`
- `node --import=extensionless/register --experimental-vm-modules node_modules/jest/bin/jest.js --runInBand __tests__/service/contractService.query.test.js`

### Still required on Docker-enabled CI / workstation

- `JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :importer:test --tests 'org.hiero.mirror.importer.parser.record.entity.sql.SqlEntityListenerTest'`
- REST integration test slice covering `ContractService.getContractTransactionDetailsByHash`

**Known limitation on this workstation**

- Testcontainers cannot start (`Could not find a working container runtime strategy`), so importer/REST integration suites cannot execute here

---

## 4) DEPLOYMENT PLAN

### Testnet

1. Merge branch `fix/blockscout-failed-eth-hash-receipts` to `main`
2. Build/publish mirror image from `main`
3. Roll out testnet mirror importer + REST
4. Run duplicate cleanup SQL after the new image is live
5. Verify `entity_id = 0` duplicates stop growing

### Mainnet

1. FRA canary rollout first
2. Verify `/api/v1/contracts/results/<hash>` for the known reproducer hash
3. Run the safe duplicate cleanup SQL on FRA
4. Continue to ASH then TYO only if FRA stays clean

---

## 5) VERIFICATION QUERIES

### Existing duplicates

```sql
SELECT count(*)
FROM contract_transaction_hash bad
WHERE bad.entity_id = 0
  AND EXISTS (
    SELECT 1
    FROM contract_transaction_hash good
    WHERE good.hash = bad.hash
      AND good.consensus_timestamp = bad.consensus_timestamp
      AND good.entity_id <> 0
  );
```

### No fresh zero-entity writes after deploy

```sql
SELECT count(*)
FROM contract_transaction_hash
WHERE entity_id = 0
  AND consensus_timestamp >= <deploy_consensus_timestamp_floor>;
```

### REST smoke

```bash
curl -s https://api.goliath.net/api/v1/contracts/results/0x46905c65f53cd8d3115c1cfe62e9a4b7961ff48866329d7a64230e85ca99ed2d
```

Expected: HTTP 200 body for the corrected hash path.

---

## 6) ROLLBACK

- Revert the mirror image to the prior digest
- Skip cleanup SQL if rollback happens before the delete step
- If cleanup SQL already ran and rollback is needed, restore from DB backup rather than attempting manual reinsertion
