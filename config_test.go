package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestTableNamesAllowSchemaAndRejectSQLFragments(t *testing.T) {
	dir := t.TempDir()
	validPath := filepath.Join(dir, "valid.json")
	valid := `{
      "oracle":{"host":"db","service_name":"svc","username":"u","password":"p"},
      "tables":{"schedule":"OPS.FAB_SCHEDULE","level_desc":"LEVEL_DESC","fab_plan":"FAB_PLAN","fab_dependency":"FAB_DEPN"},
      "monitor":{"process_date":"20251231"}
    }`
	if err := os.WriteFile(validPath, []byte(valid), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := LoadConfig(validPath, dir); err != nil {
		t.Fatalf("schema-qualified table names should be accepted: %v", err)
	}

	invalidPath := filepath.Join(dir, "invalid.json")
	invalid := `{
      "oracle":{"host":"db","service_name":"svc","username":"u","password":"p"},
      "tables":{"schedule":"FAB_SCHEDULE; DROP TABLE X"},
      "monitor":{"process_date":"20251231"}
    }`
	if err := os.WriteFile(invalidPath, []byte(invalid), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := LoadConfig(invalidPath, dir); err == nil {
		t.Fatal("SQL fragments in table names must be rejected")
	}
}
