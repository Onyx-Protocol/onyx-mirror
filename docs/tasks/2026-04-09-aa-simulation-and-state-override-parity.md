# Mirror Web3: AA Simulation and State Override Parity

## Context

What you need to know to complete this task:

- Thirdweb's remaining Goliath blocker is not transaction submission; it is simulation parity for `eth_call` / `eth_estimateGas`.
- Current `main` still rejects the exact problematic cases:
  - non-existent `from` address,
  - contract sender as payer during simulation.
- **Critical architecture insight:** Sender rejection happens at TWO independent layers:
  1. `TransactionExecutionService.getSenderAccountID()` (lines 244-268) — mirror-web3 pre-check. Commit `9d8cb8166` already removed the contract-sender check here, but the non-existent sender check remains in two places:
     - alias path: `getSenderAccountIDAsNum()` throws when alias lookup returns null (lines 274-276),
     - long-zero / derived account path: `getSenderAccountID()` throws when `accountReadableKVState.get(accountIDNum)` returns null (lines 252-254).
  2. `SolvencyPreCheck.getPayerAccount()` — inside the Hedera services executor, called during `TransactionExecutor.execute()`. Even after `9d8cb8166`, this second layer still rejects contract senders with "payer account ... is a smart contract". The fix must substitute a valid payer (treasury) BEFORE the executor runs.
- `ContractCallRequest.java` (lines 20-46) has no `stateOverride` field. State override support requires extending the request model, controller, execution parameters, AND the EVM world state view in the execution framework.
- The mainnet issue file is `~/goliath/mainnet/docs/issues/2026-04-09-thirdweb-aa-partner-enablement-gap.md`.
- Current evidence:
  - `web3/src/main/java/org/hiero/mirror/web3/service/TransactionExecutionService.java:244-276`
  - `web3/src/test/java/org/hiero/mirror/web3/service/ContractCallServiceTest.java:474-485`
  - `web3/src/test/java/org/hiero/mirror/web3/service/ContractCallServiceTest.java:508-522`
- Thirdweb also requested balance/code state overrides. There is currently no implementation in `rest/` or `web3/`.

## Task

Implement mirror-web3 behavior that matches Ethereum-style simulation expectations closely enough for thirdweb AA flows.

Execute in this order:

1. Create `fix/thirdweb-aa-simulation-parity` from `main`.
2. Review historical commits `9d8cb8166` and `6cc4259fc` before touching code.
   - `9d8cb8166` is in `HEAD`. It removed the `account.smartContract()` rejection from `getSenderAccountID()`, but the error persists because `SolvencyPreCheck` in the Hedera services executor provides a second-layer rejection. This commit alone is NOT sufficient.
   - `6cc4259fc` (upstream, not in `HEAD`) is the correct fix pattern. It re-adds the `account.smartContract()` check in `getSenderAccountID()` but returns the treasury account as payer instead of throwing:
     ```java
     } else if (account.smartContract()) {
         return EntityIdUtils.toAccountId(systemEntity.treasuryAccount());
     }
     ```
     This works because the treasury is a valid non-contract account, so `SolvencyPreCheck` passes. The commit's test renames `...Fails` → `...Succeeds`.
   - Apply the same treasury-substitution approach for non-existent senders in BOTH missing-sender branches:
     - alias lookup null in `getSenderAccountIDAsNum()` (lines 274-276),
     - account lookup null in `getSenderAccountID()` (lines 252-254).
   - **The `msg.sender` question:** Treasury substitution changes who pays fees in the executor, but the EVM execution context should still use the original `from` as `msg.sender`. The regression test in step 5 is the definitive verification of this property.
3. Update `web3/src/main/java/org/hiero/mirror/web3/service/TransactionExecutionService.java` so simulation no longer fails when `from` is:
   - a counterfactual / non-existent sender,
   - a contract sender.
4. Keep actual submission semantics unchanged. This task only changes `eth_call` / `eth_estimateGas`.
5. Add a regression test proving the logical EVM sender remains the requested `from` address during simulation. If a payer-substitution workaround changes `msg.sender`, refine it before shipping.

   **Recommended PR split:** Ship steps 1-5 (sender parity) as a separate PR and deploy immediately. Sender parity is the primary thirdweb blocker and a straightforward treasury-substitution change (~10 lines of production code + test updates). Steps 6-11 (state overrides) require deeper Besu/Hedera EVM framework changes and should be a follow-up PR.

   > **Complexity warning for steps 6-11:** State overrides require modifying the EVM world state view in the Besu/Hedera execution framework. This means injecting override values into the account store (for `balance`) and contract bytecode store (for `code`) before simulation, then discarding them. The chain is: `ContractCallRequest` → `ContractController` → execution parameters → world state overlay. The world state overlay is the hardest part — it's not just adding a field to the request model. If this blocks the first deployment, ship sender parity alone and track state overrides as a dependent follow-up with an explicit "unsupported" rejection in the interim.

6. Extend the mirror-web3 request path to carry a first-cut `stateOverride` payload:
   - `web3/src/main/java/org/hiero/mirror/web3/viewmodel/ContractCallRequest.java`
   - `web3/src/main/java/org/hiero/mirror/web3/controller/ContractController.java`
   - the relevant execution parameter / context classes used by `ContractExecutionService`
7. Implement a simulation-only override contract for:
   - per-address `balance`
   - per-address `code`
8. Reject unsupported override fields explicitly:
   - `nonce`
   - `state`
   - `stateDiff`
   - unknown keys
9. Ensure the override overlay is reapplied during every binary-search iteration used by `eth_estimateGas`.
10. Update or add tests in:

- `web3/src/test/java/org/hiero/mirror/web3/service/ContractCallServiceTest.java`
- `web3/src/test/java/org/hiero/mirror/web3/controller/ContractControllerTest.java`

11. Run `./gradlew :web3:test` and capture any residual limitation explicitly if estimate-gas overrides need to stay rejected in the first cut.

## Blockers

No blockers.

## Acceptance Checklist

- [ ] `eth_call` with a non-existent sender no longer throws `Sender account not found.`
- [ ] `eth_estimateGas` with a non-existent sender returns a gas estimate
- [ ] `eth_call` with a contract sender no longer fails with `payer account ... is a smart contract`
- [ ] `eth_estimateGas` with a contract sender returns a gas estimate
- [ ] A regression test proves the logical EVM sender remains the requested `from` address during simulation
- [ ] State override support is implemented and documented at the mirror layer for `balance` and `code`, or an explicit estimate-gas rejection contract is codified with tests
- [ ] Unsupported override fields are rejected explicitly instead of being ignored
- [ ] Actual submission semantics remain unchanged
- [ ] JUnit/integration tests are written and passing
- [ ] Code follows the project's style
