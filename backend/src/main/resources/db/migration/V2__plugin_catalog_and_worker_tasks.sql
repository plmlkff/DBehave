CREATE TABLE dbehave.plugins (
    id varchar(128) PRIMARY KEY,
    definition jsonb NOT NULL,
    CONSTRAINT plugin_definition_object CHECK (jsonb_typeof(definition) = 'object'),
    CONSTRAINT plugin_definition_id CHECK (definition ->> 'id' IS NOT NULL AND definition ->> 'id' = id)
);

CREATE SCHEMA worker;

CREATE TABLE worker.tasks (
    id uuid PRIMARY KEY,
    state varchar(64) NOT NULL DEFAULT 'QUEUED',
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    context jsonb NOT NULL,
    heartbeat timestamptz,
    worker_id varchar(255),
    error text,
    CONSTRAINT task_state_not_empty CHECK (length(trim(state)) > 0),
    CONSTRAINT task_execution_context CHECK (
        jsonb_typeof(context) = 'object'
        AND context ?& ARRAY['loaderImageName', 'databaseImageName', 'loaderImageContext', 'databaseImageContext']
        AND jsonb_typeof(context -> 'loaderImageName') = 'string'
        AND jsonb_typeof(context -> 'databaseImageName') = 'string'
        AND jsonb_typeof(context -> 'loaderImageContext') = 'object'
        AND jsonb_typeof(context -> 'databaseImageContext') = 'object'
    )
);

CREATE INDEX tasks_queued_created_at_idx ON worker.tasks (created_at, id) WHERE state = 'QUEUED';

CREATE FUNCTION worker.touch_task_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER tasks_updated_at
BEFORE UPDATE ON worker.tasks
FOR EACH ROW EXECUTE FUNCTION worker.touch_task_updated_at();
