Sharding-aware resources SQL bundle for the 12306 project.

Files included:
- db/12306-springcloud-ticket.sql
- db/12306-seat-bitmap-migration.sql
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
- ticket DB and data are the bitmap-seat version.
- For a clean rebuild, import the db/*.sql files first, then data/*.sql.
- If rebuilding from scratch with db/12306-springcloud-ticket.sql + data/12306-springcloud-ticket.sql, you do not need db/12306-seat-bitmap-migration.sql.
