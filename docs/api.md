# Workspace API subset

Base: `/api/2.0/workspace`. All requests need local bearer authentication. Editors/admins write; viewers/end_user identities read tenant-shared objects. Errors use `{error_code, message}`.

| Method | Route | Contract |
|---|---|---|
| POST | /mkdirs | `{path}` recursively creates directories, idempotently |
| GET | /get-status | `path` query returns path, object_id, object_type and notebook language |
| GET | /list | `path` lists immediate children; notebook paths return that notebook |
| POST | /import | `{path, content, language, format?, overwrite?}` imports a SOURCE notebook |
| GET | /export | `path`, optional `format=SOURCE`; returns base64 content |
| POST | /delete | `{path, recursive?}` deletes a notebook or directory |

Import language is PYTHON, SQL, SCALA or R. Format defaults to SOURCE; overwrite defaults false and must be boolean. Parent directories must exist. Replacing a notebook preserves its object ID. Content is base64 UTF-8 with a local 10 MiB encoded-size ceiling; source is stored exactly and never executed. Exports preserve the original encoded content. Root `/` is virtual and protected. Paths must be normalized absolute paths, without empty components, traversal, backslashes or control characters. These are database keys, never host filesystem paths.

Unsupported formats, directory exports, file/library operations, direct_download and modified-since listing fail explicitly. Recursive deletion is atomic locally, a documented difference from Databricks. No jobs, clusters or notebook execution endpoints are advertised.

`GET /api/2.0/workspace/export?path=/book&direct_download=true` returns exact decoded source bytes as an `application/octet-stream` attachment named `notebook.txt`. Omitted or `false` returns the existing base64 JSON. Only lowercase `true`/`false` are accepted; repeated/unknown options fail. SOURCE-only, notebook-only, tenant and authentication boundaries remain in force. No format conversion, directory archives or size-limit expansion.
