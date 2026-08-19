Sharding-aware resources SQL bundle for the 12306 project.

Files included:
- db/12306-springcloud-ticket.sql
- db/12306-springcloud-user.sql
- db/12306-springcloud-order.sql
- db/12306-springcloud-pay.sql
- data/12306-springcloud-ticket.sql
- data/12306-springcloud-user.sql

Notes:
- ticket-service uses single DB: 12306_ticket
- user-service uses sharded DBs: 12306_user_0, 12306_user_1
- order-service uses sharded DBs: 12306_order_0, 12306_order_1
- pay-service uses sharded DBs: 12306_pay_0, 12306_pay_1
- For user and order tables, shards 0-15 are created in the _0 database and shards 16-31 in the _1 database.
- All db/*.sql files are the current baseline schema, including the seat bitmap, service-date inventory,
  reliable command, asynchronous order, balance payment, and refund idempotency structures.
- For a clean rebuild, import the db/*.sql files first, then data/*.sql.
- The bundle does not provide an in-place upgrade path for an existing database. Rebuild the local database
  from the baseline scripts when the schema needs to be refreshed.
