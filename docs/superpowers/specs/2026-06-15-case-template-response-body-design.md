> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# Case Template Response Body Design

## Goal

Make case template visibility affect actual generated case data, add expected/actual response body fields, support response body parameter match thresholds, and provide bulk field maintenance for large request fields.

## Design

- Template field visibility is treated as a generation contract for configurable row data. Hidden fields are cleared from generated case rows and from executable metadata for request payload fields so hidden content is not carried into automation.
- Interface case templates include two built-in JSON fields: `预期返回体` and `实际返回体`.
- OpenAPI response examples and schemas are parsed into `expectedResponseBody`. Generated positive cases copy that value into rows and metadata.
- API automation compares expected response body parameter paths with actual response body parameter paths. The threshold is adjustable from 0 to 100 percent, defaulting to 100 percent.
- Bulk field modification operates on the filtered case set, then applies only to records whose selected match field equals or contains the given value.
- Field assignment scans `路径参数`, `查询参数`, and `请求体` JSON values from the selected or filtered rows, exposes discovered JSON paths, and updates matching JSON paths in both row data and executable metadata.

## Notes

- The UI remains compact and enterprise oriented: dialog-based edits, small form controls, and no large explanatory blocks.
- MeterSphere and Apifox are used as product references for environment-driven API execution, response body assertions, and response model validation, but this node implements a lightweight local version first.
