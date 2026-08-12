# Onyx Mirror Node

Mirror node stack for **Onyx Mainnet** — ingests consensus record streams and serves REST / gRPC / Web3 APIs for the EVM-compatible Onyx L1 (chain ID `327`, native **XCN**).

This repository is a fork of [Hiero mirror node](https://github.com/hiero-ledger/hiero-mirror-node) (Hedera / Hiero stack) with Onyx-specific importer, REST, and Web3 changes.

## Interact via the EVM layer

For application development, prefer the **EVM JSON-RPC** endpoint (relay in front of this mirror), not the Hedera SDK as the primary path:

- HTTP RPC: `https://rpc.onyx.org`
- WebSocket: `wss://ws.onyx.org`
- Mirror REST: `https://api.onyx.org`

Full developer documentation: **[https://docs.onyx.org/](https://docs.onyx.org/)**

## Network parameters

| Parameter    | Value                                |
| ------------ | ------------------------------------ |
| Native token | XCN                                  |
| EVM chain ID | `327` (`0x147`)                      |
| Ledger ID    | `0x47` (legacy technical identifier) |

## Upstream

- Upstream: [`hiero-ledger/hiero-mirror-node`](https://github.com/hiero-ledger/hiero-mirror-node)
- Apache 2.0 — retain upstream attribution.

Names containing `goliath` (repos, GHCR paths, namespaces) are **legacy technical identifiers**; the network name is **Onyx**.

## Deployed mainnet images (live pins)

| Component        | Image                                                                                                                                                                                             |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Importer         | `ghcr.io/onyx-protocol/goliath-mirror/mirror-importer@sha256:6f7f64f865a22e31b93d18fd7aee8a81f693cb665b8ff026efbe2e320e850280` (tag `2ec9276`, source `2ec92762282e04d46d00a1ff957dd5061309089c`) |
| REST             | `ghcr.io/onyx-protocol/goliath-mirror/mirror-rest:main@sha256:0eb170983fbc8c0df75d2f5189fbc289fbcc6c7446770ab7f15e22913d15c04a`                                                                   |
| Web3             | `ghcr.io/crypt0grapher/goliath-mirror/mirror-web3:aa-contract-sender-fix-22e51c454` (source `22e51c454…`)                                                                                         |
| gRPC / rest-java | Stock `gcr.io/mirrornode/hedera-mirror-*:0.149.0` (upstream images)                                                                                                                               |

Git tags: `onyx-mainnet-importer-2ec9276`, `onyx-mainnet-web3-22e51c454`.

## Onyx fork images (build)

```bash
# CI builds importer + rest (see .github/workflows/build-mirror-images.yaml)
docker pull ghcr.io/onyx-protocol/goliath-mirror/mirror-importer:main
docker pull ghcr.io/onyx-protocol/goliath-mirror/mirror-rest:main
```

## License

Apache License 2.0 — see [LICENSE](LICENSE).
