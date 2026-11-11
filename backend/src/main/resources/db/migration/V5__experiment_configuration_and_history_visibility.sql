ALTER TABLE dbehave.experiments
    ADD COLUMN configuration jsonb,
    ADD COLUMN grafana_url_template text;

UPDATE dbehave.experiments
SET grafana_url_template = replace(grafana_url, task_id::text, '{runId}')
WHERE strpos(grafana_url, task_id::text) > 0;

CREATE TABLE dbehave.hidden_experiments (
    task_id uuid PRIMARY KEY REFERENCES worker.tasks (id) ON DELETE CASCADE
);
