
-- Safe migration: set default 0 and not null for translation_memory.score if table exists
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name='translation_memory') THEN
    BEGIN
      ALTER TABLE translation_memory ALTER COLUMN score SET DEFAULT 0;
    EXCEPTION WHEN others THEN
      -- ignore
      NULL;
    END;
    BEGIN
      UPDATE translation_memory SET score = 0 WHERE score IS NULL;
    EXCEPTION WHEN others THEN
      NULL;
    END;
    BEGIN
      ALTER TABLE translation_memory ALTER COLUMN score SET NOT NULL;
    EXCEPTION WHEN others THEN
      NULL;
    END;
  END IF;
END $$;
