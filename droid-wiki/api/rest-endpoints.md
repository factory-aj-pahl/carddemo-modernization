# REST endpoints

Besides the screen API, the online API has a handful of plain JSON endpoints: the current session, report runs and their download, the two inquiry services from the VSAM-MQ module, the authorization decision from the IMS/Db2/MQ module, and the infrastructure endpoints for health and the OpenAPI document. This page lists every one of them with its access rule and status codes.

## Overview

The list below is the complete set of non-screen mappings found by searching for `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, and `@RequestMapping` under `modernization/backend/*/src/main`. There are no `PUT`, `PATCH`, or `DELETE` endpoints anywhere. The `/api/users`, `/api/cards`, and `/api/accounts` sketches in `modernization/architecture.md` section 3 were not built (that section says so); those operations exist only as screens ([Screen API](screen-api.md)).

Access rules come from `modernization/backend/iam/src/main/java/com/carddemo/iam/security/SecurityConfig.java` plus method-level `@PreAuthorize`, and match the classification in `modernization/backend/app/src/test/java/com/carddemo/app/SecurityMatrixTest.java`.

## Endpoint table

| Method | Path | Controller | Access | Purpose | Status codes |
|:--|:--|:--|:--|:--|:--|
| GET | `/api/session` | `modernization/backend/iam/src/main/java/com/carddemo/iam/web/SessionController.java` | Path open; answers only with a session | The signed-on user: `userId`, `role` (`ADMIN` or `USER`), `firstName`, `lastName`. Replaces reading the COMMAREA user fields | 200, 401 |
| GET | `/api/reports` | `modernization/backend/reporting/src/main/java/com/carddemo/reporting/web/ReportRunController.java` | Signed on; a USER sees own runs, an ADMIN every run | Recent report runs, newest first, as `RunView` records | 200, 401 |
| GET | `/api/reports/{runId}` | same | Signed on; own run or ADMIN | One run with its current state, refreshed from Airflow when it changed | 200, 401, 404 (not visible or unknown) |
| GET | `/api/reports/{runId}/TRANREPT.txt` | same | Signed on; own run or ADMIN | The TRANREPT file as `text/plain`, `Content-Disposition: attachment; filename=<runId>-TRANREPT.txt` | 200, 401, 404 (not visible or not written yet) |
| GET | `/api/inquiry/account/{id}` | `modernization/backend/inquiry/src/main/java/com/carddemo/inquiry/web/AccountInquiryController.java` | Signed on; scoped to linked accounts | REST twin of COACCT01: account fields with decimal amounts and ISO dates | 200, 400 (ID not 11 digits or all zeros), 401, 404 (missing or out of scope), 503 (account store unreadable) |
| GET | `/api/inquiry/date` | `modernization/backend/inquiry/src/main/java/com/carddemo/inquiry/web/DateInquiryController.java` | Signed on | REST twin of CODATE01: `{"timestamp": "..."}`, ISO 8601 local date-time with UTC offset | 200, 401 |
| POST | `/api/authorizations` | `modernization/backend/authorization/src/main/java/com/carddemo/authorization/web/AuthorizationController.java` | ADMIN (`@PreAuthorize("hasRole('ADMIN')")`); CSRF header required | REST twin of COPAUA0C: decide one authorization request and record it as pending | 200, 401, 403, 503 (critical error; body `{location, message}`, nothing recorded) |
| GET | `/actuator/health` | Spring Boot Actuator | Public | `{"status":"UP"}` when the service and its dependencies are healthy. Only `health` is exposed (`management.endpoints.web.exposure.include=health`) | 200, 503 |
| GET | `/v3/api-docs` | springdoc | Public | The generated OpenAPI document | 200 |
| GET | `/swagger-ui/**`, `/swagger-ui.html` | springdoc | Public | Swagger UI over the same document | 200 |

The billpay-service on 8091 exposes `/api/session`, `/actuator/health`, and `/v3/api-docs` from this list and none of the others (`modernization/backend/billpay-service/src/test/java/com/carddemo/billpayservice/it/StandaloneSecurityMatrixIT.java` classifies its full operation set).

## Details per endpoint

### Session

`SessionController.session()` returns `CurrentUser.current()` or 401. The path is in the `permitAll` list so that the web app can ask without first getting a 401 from the filter chain, but the controller itself still answers 401 when nobody is signed on. `currentSession()` in `modernization/web/src/api.ts` turns that 401 into `null`. Sign-on and sign-off are screen turns on `/api/screens/COSGN00C`, not REST calls; see [Sign-on and sessions](../features/sign-on-and-sessions.md).

### Report runs

A run is created by a `POST` turn on the CORPT00C screen, which writes a `report_run` row and triggers the `carddemo_transaction_report` DAG through the Airflow REST API (`modernization/backend/reporting/src/main/java/com/carddemo/reporting/internal/AirflowReportLauncher.java`). The row is the access check for later reads (`modernization/backend/schema/src/main/resources/db/migration/V40__report_run.sql`). `RunView` (`modernization/backend/reporting/src/main/java/com/carddemo/reporting/web/RunView.java`) carries `runId`, `reportType`, `startDate`, `endDate`, `userId`, `requestedAt`, `state`, and `downloadUrl`, the last only once the file exists. `ReportService` asks Airflow for the state on each read and stores a changed state with `state_checked_at`. The end-to-end flow is in [Transaction reports](../features/transaction-reports.md).

### Account and date inquiry

`GET /api/inquiry/account/{id}` takes the ID as the queue does (C-KEYLEN): exactly 11 digits, not all zeros, never left-padded. Error bodies are `{"message": ...}` with the queue's own reply text, so a 400 shows the invalid-request text the queue would return for `INQA` followed by the same ID. Success returns `AccountReply`: `accountId`, `activeStatus`, `currentBalance`, `creditLimit`, `cashCreditLimit`, `openDate`, `expirationDate`, `reissueDate`, `currentCycleCredit`, `currentCycleDebit`, `groupId`, with amounts at two decimal places. Code detail is in [Inquiry](../modules/inquiry.md).

### Authorization decision

The request body has the 18 fields of ENT-AUTH-REQUEST as strings (`authDate`, `authTime`, `cardNum`, `authType`, `cardExpiryDate`, `messageType`, `messageSource`, `processingCode`, `transactionAmt`, `merchantCategoryCode`, `acqrCountryCode`, `posEntryMode`, `merchantId`, `merchantName`, `merchantCity`, `merchantState`, `merchantZip`, `transactionId`). The response has `cardNum`, `transactionId`, `authIdCode`, `respCode`, `reasonCode`, and a decimal `approvedAmount`. It calls the same `AuthorizationEngine` as the queue consumer, so a REST request and a queue request with the same content get the same decision. The endpoint is ADMIN-only because its callers act for the card network (Javadoc of `AuthorizationController`). See [Authorization](../modules/authorization.md) and [Pending authorizations](../features/pending-authorizations.md).

### Health and probes

`modernization/backend/app/src/main/resources/application.properties` also sets `management.endpoint.health.probes.enabled=true`, which makes Spring Boot add `/actuator/health/liveness` and `/actuator/health/readiness`. `SecurityConfig` permits only the exact path `/actuator/health`, and the chain ends with `anyRequest().denyAll()`, so the probe sub-paths are not reachable anonymously. The ops scripts and `modernization/ops/check_stack.py` only use `/actuator/health`. See [How to monitor](../how-to-monitor.md).

## Common rules

- Every `POST` must carry `X-XSRF-TOKEN` matching the `XSRF-TOKEN` cookie; a missing token is 403.
- A request outside `/api/**` and the infrastructure paths is denied, whatever the session.
- There is no API versioning, no pagination on `/api/reports` beyond "recent runs", and no rate limiting on HTTP (the rate limit applies only to the inquiry queues, see [Message queues](message-queues.md)).

## Related pages

- [API](index.md) for the overview of interface styles
- [Security](../security.md) for sessions, roles, and scoping
- [Online API](../applications/online-api.md) for how these controllers are assembled
