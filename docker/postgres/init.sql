-- Spring AI can create these extensions too, but initializing them here makes
-- the local database independently verifiable before the application starts.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
