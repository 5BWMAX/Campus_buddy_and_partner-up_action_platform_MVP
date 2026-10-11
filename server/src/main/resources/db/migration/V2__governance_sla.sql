-- Durable, deduplicated record of a missed first-response deadline.
CREATE TABLE governance_sla_event (
  report_id uuid PRIMARY KEY REFERENCES report(id),
  deadline_at timestamptz NOT NULL,
  recorded_at timestamptz NOT NULL
);
CREATE TRIGGER immutable_sla_event BEFORE UPDATE OR DELETE ON governance_sla_event
FOR EACH ROW EXECUTE FUNCTION prevent_history_change();
