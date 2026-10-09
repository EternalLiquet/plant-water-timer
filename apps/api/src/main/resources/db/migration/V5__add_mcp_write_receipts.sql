CREATE TABLE mcp_write_receipts (
  owner_id UUID NOT NULL,
  operation VARCHAR(40) NOT NULL,
  request_id UUID NOT NULL,
  plant_id UUID NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (owner_id, operation, request_id),
  CONSTRAINT mcp_receipt_owned_plant FOREIGN KEY (plant_id, owner_id) REFERENCES plants (id, owner_id)
);
