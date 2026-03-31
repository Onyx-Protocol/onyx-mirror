-- Remove child hash from contract_transaction_hash so it's only findable via
-- getContractCreateChildByParentConsensusTimestamp (same setup as the CREATE test)
delete from contract_transaction_hash
where consensus_timestamp = 167654000223457
  and entity_id = 5002;
