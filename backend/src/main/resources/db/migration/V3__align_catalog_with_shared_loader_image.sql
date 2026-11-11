-- Exact generated PostgreSQL definition from fcf0851, canonicalized as PostgreSQL jsonb.
-- The initializer replaces this seed with the current definition after migrations.
-- Any customized definition is preserved.
DELETE FROM dbehave.plugins
WHERE id = 'postgres'
  AND md5(definition::text) = 'bcbe0c8ae07ae5530ee315476a2dc7f7';

-- Scenario selection now uses SCENARIO inside one shared loader image.
-- Keep custom image references intact so owners can choose the appropriate image.
UPDATE dbehave.plugins AS plugin
SET definition = jsonb_set(
    plugin.definition,
    '{scenarios}',
    (
        SELECT jsonb_agg(scenario.value - 'loadGeneratorTag' ORDER BY scenario.position)
        FROM jsonb_array_elements(plugin.definition -> 'scenarios') WITH ORDINALITY AS scenario(value, position)
    )
)
WHERE jsonb_typeof(plugin.definition -> 'scenarios') = 'array'
  AND EXISTS (
      SELECT 1
      FROM jsonb_array_elements(plugin.definition -> 'scenarios') AS scenario(value)
      WHERE scenario.value ? 'loadGeneratorTag'
  );
