# Databricks compatibility ledger

Reference inventory: `Databricks Product, UI and API Inventory and Open Source Parity Check (November 2026).md`. The installed `open-bricks` skill contains the inventory and development workflow. The inventory is a planning source, not a complete runtime contract.

Target: Workspace API 2.0, primary documentation inspected 2026-11-13: [Workspace import](https://docs.databricks.com/api/workspace/workspace/import), [Workspace SDK](https://github.com/databricks/databricks-sdk-py/blob/main/databricks/sdk/service/workspace.py). The Java HTTP tests preserve the captured local Workspace API contracts, including direct downloads. They do not run a vendor SDK or contact a Databricks account.

| ID | Inventory area | Status | Boundary / next work |
|---|---|---|---|
| OB-001 | Workspace and notebooks | Implemented subset | mkdirs, status, list, SOURCE import/export (JSON or direct bytes), delete |
| OB-002 | Identity and access | Partial | Local tokens, tenant isolation, editor/viewer roles; no OAuth/SCIM or object ACLs |
| OB-003 | Notebook authoring | Partial | UTF-8 source round trips; no DBC/Jupyter/HTML, collaboration or UI |
| OB-004 | Compute and Jobs | Missing | No fabricated Spark runtime, clusters, jobs or results |
| OB-005 | SQL and analytics | Missing | Warehouses, SQL execution, dashboards |
| OB-006 | Lakehouse and governance | Missing | Delta, Unity Catalog, volumes, lineage and sharing |
| OB-007 | AI/ML and apps | Missing | MLflow, serving, feature store, apps and assistants |
| OB-008 | Production operation | Missing | SSO, quotas, backup/migration tooling and distributed execution |

Acceptance covers recursive/idempotent creation, source round trip, overwrite identity, malformed inputs, prefix-safe deletion, tenant/role boundaries, restart persistence, concurrent creates and direct byte downloads. Local IDs, stricter paths, encoded-size limits and atomic recursive deletion are explicit deviations. This is neither complete Databricks parity nor licensed-instance conformance.

## OB-001 continuation — direct notebook download, 13 November 2026

Baseline `9ffc934`, main, clean tree. Selected the missing direct_download flag over Jupyter conversion (OB-003, missing) and compute (OB-004, missing). Contract: Workspace API 2.0 [official export reference](https://docs.databricks.com/api/workspace-files/v1/export), retrieved 2026-11-13, documents raw export when true and base64 JSON otherwise. Content-Disposition and the generic filename are local choices, not verified vendor headers.

Implementation `ApiController.java`; tests in `ContractTest.java`. Acceptance verifies empty/Unicode/CRLF/HTML-like source byte equality, restart, authentication/tenant boundaries, malformed flags and format/path restrictions. The Java HTTP suite verifies direct downloads; the earlier SDK journey is historical evidence. Three positive cases failed before implementation. Current Java measurements and reproduction: [validation](validation.md).

## Java 25 migration

The runtime, HTTP acceptance harness and build now use Java 25 / Spring Boot 4.1.2. Existing persistence and implemented API behavior are retained. Current coverage and test results supersede earlier stack-specific measurements: see [validation](validation.md). This migration does not expand vendor-compatibility claims.
