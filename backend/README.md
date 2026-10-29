# Backend API

Run locally with Java 21 and Maven. Configure PostgreSQL connection variables and set ADMIN_PASSWORD to a strong value before startup.

Public endpoints: GET /api/dashboard/dates, GET /api/dashboard/msu, GET /api/dashboard/organizations?msuId=1, GET /api/dashboard/statistics?date=2026-10-28&msuId=1.

Protected endpoints: POST /api/admin/imports (multipart field file) and GET /api/admin/imports. Both require HTTP Basic credentials.

The snapshot date is calculated from the current server date in APP_TIME_ZONE. Excel is processed from a temporary file, and the temporary file is deleted after processing.
