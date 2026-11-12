-- One generated ID for each task and its experiment. Keep Prometheus labels for old runs.
LOCK TABLE worker.tasks, dbehave.experiments, dbehave.hidden_experiments IN ACCESS EXCLUSIVE MODE;

ALTER TABLE worker.tasks DISABLE TRIGGER tasks_updated_at;
ALTER TABLE worker.tasks ADD COLUMN numeric_id bigserial;
ALTER TABLE worker.tasks ADD COLUMN legacy_run_id text UNIQUE;

-- Assign existing runs in creation order without changing their timestamps or status.
UPDATE worker.tasks AS task
SET numeric_id = ordered.id, legacy_run_id = task.id::text
FROM (SELECT id AS old_id, row_number() OVER (ORDER BY created_at, id) AS id FROM worker.tasks) AS ordered
WHERE task.id = ordered.old_id;

ALTER TABLE dbehave.experiments ADD COLUMN numeric_task_id bigint;
UPDATE dbehave.experiments AS experiment
SET numeric_task_id = task.numeric_id FROM worker.tasks AS task WHERE task.id = experiment.task_id;
ALTER TABLE dbehave.hidden_experiments ADD COLUMN numeric_task_id bigint;
UPDATE dbehave.hidden_experiments AS hidden
SET numeric_task_id = task.numeric_id FROM worker.tasks AS task WHERE task.id = hidden.task_id;

ALTER TABLE dbehave.experiments DROP CONSTRAINT experiments_task_id_fkey;
ALTER TABLE dbehave.hidden_experiments DROP CONSTRAINT hidden_experiments_task_id_fkey;
ALTER TABLE dbehave.experiments DROP COLUMN task_id;
ALTER TABLE dbehave.hidden_experiments DROP COLUMN task_id;
ALTER TABLE worker.tasks DROP COLUMN id;

ALTER TABLE worker.tasks RENAME COLUMN numeric_id TO id;
ALTER SEQUENCE worker.tasks_numeric_id_seq RENAME TO tasks_id_seq;
SELECT setval('worker.tasks_id_seq', COALESCE((SELECT max(id) FROM worker.tasks), 1),
              EXISTS (SELECT 1 FROM worker.tasks));
ALTER TABLE worker.tasks ADD PRIMARY KEY (id);

ALTER TABLE dbehave.experiments RENAME COLUMN numeric_task_id TO task_id;
ALTER TABLE dbehave.experiments ADD PRIMARY KEY (task_id);
ALTER TABLE dbehave.experiments ADD FOREIGN KEY (task_id) REFERENCES worker.tasks (id) ON DELETE CASCADE;
ALTER TABLE dbehave.hidden_experiments RENAME COLUMN numeric_task_id TO task_id;
ALTER TABLE dbehave.hidden_experiments ADD PRIMARY KEY (task_id);
ALTER TABLE dbehave.hidden_experiments ADD FOREIGN KEY (task_id) REFERENCES worker.tasks (id) ON DELETE CASCADE;

CREATE INDEX tasks_queued_created_at_idx ON worker.tasks (created_at, id) WHERE state = 'QUEUED';
CREATE INDEX tasks_created_at_idx ON worker.tasks (created_at DESC, id DESC);
ALTER TABLE worker.tasks ENABLE TRIGGER tasks_updated_at;
