CREATE TABLE dbehave.experiments (
    task_id uuid PRIMARY KEY REFERENCES worker.tasks (id) ON DELETE CASCADE,
    plugin_id varchar(128) NOT NULL,
    plugin_title text NOT NULL,
    scenario_id text NOT NULL,
    scenario_title text NOT NULL,
    grafana_url text NOT NULL
);

CREATE INDEX tasks_created_at_idx ON worker.tasks (created_at DESC, id DESC);
