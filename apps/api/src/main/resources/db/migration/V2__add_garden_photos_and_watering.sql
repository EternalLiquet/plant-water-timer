CREATE TABLE garden_photos (
 id UUID PRIMARY KEY,
 owner_id UUID NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
ALTER TABLE plants ADD COLUMN photo_id UUID REFERENCES garden_photos(id);
ALTER TABLE plants ADD COLUMN client_request_id UUID;
CREATE UNIQUE INDEX plants_client_request ON plants(owner_id, client_request_id);
CREATE TABLE watering_events (
 id UUID PRIMARY KEY,
 plant_id UUID NOT NULL,
 owner_id UUID NOT NULL,
 watered_date DATE NOT NULL,
 zone_id VARCHAR(80) NOT NULL,
 next_check DATE NOT NULL,
 rule_version VARCHAR(80) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 undone_at TIMESTAMP WITH TIME ZONE,
 CONSTRAINT watering_plant_owner FOREIGN KEY (plant_id, owner_id) REFERENCES plants(id, owner_id)
);
CREATE INDEX watering_owner_plant_date ON watering_events(owner_id,plant_id,watered_date);
