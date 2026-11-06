// Seed data for the staging MongoDB.
// Runs once via /docker-entrypoint-initdb.d, only when the data volume is empty.

var appDb = db.getSiblingDB('myapp');

appDb.users.insertMany([
  { email: 'alice@example.com', name: 'Alice', createdAt: new Date() },
  { email: 'bob@example.com', name: 'Bob', createdAt: new Date() }
]);
appDb.users.createIndex({ email: 1 }, { unique: true });

appDb.orders.insertMany([
  { userEmail: 'alice@example.com', amount: 100.00, createdAt: new Date() },
  { userEmail: 'alice@example.com', amount: 250.50, createdAt: new Date() },
  { userEmail: 'bob@example.com', amount: 75.25, createdAt: new Date() }
]);

print('Seed data loaded into "myapp"');
