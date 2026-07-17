CREATE TABLE plants (
  id UUID PRIMARY KEY,
  owner_id UUID NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  known_name VARCHAR(100),
  environment VARCHAR(20) NOT NULL,
  pot_material VARCHAR(20) NOT NULL,
  drainage VARCHAR(20) NOT NULL,
  light_level VARCHAR(30) NOT NULL,
  baseline_inspection_interval_days INTEGER NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT plants_owner_reference UNIQUE (id, owner_id),
  CONSTRAINT plants_baseline_interval_check
    CHECK (baseline_inspection_interval_days BETWEEN 1 AND 365)
);

CREATE INDEX plants_owner_id_index ON plants (owner_id);

CREATE TABLE soil_observations (
  id UUID PRIMARY KEY,
  plant_id UUID NOT NULL,
  owner_id UUID NOT NULL,
  soil_state VARCHAR(20) NOT NULL,
  notes VARCHAR(1000),
  observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT observations_plant_owner_reference UNIQUE (id, plant_id, owner_id),
  CONSTRAINT observations_owned_plant_foreign_key
    FOREIGN KEY (plant_id, owner_id) REFERENCES plants (id, owner_id),
  CONSTRAINT observations_soil_state_check CHECK (soil_state IN ('WET', 'MOIST', 'DRY'))
);

CREATE INDEX observations_plant_owner_index
  ON soil_observations (plant_id, owner_id, observed_at);

CREATE TABLE inspection_recommendations (
  id UUID PRIMARY KEY,
  plant_id UUID NOT NULL,
  observation_id UUID NOT NULL UNIQUE,
  owner_id UUID NOT NULL,
  recommended_inspection_at TIMESTAMP WITH TIME ZONE NOT NULL,
  minimum_inspection_at TIMESTAMP WITH TIME ZONE NOT NULL,
  maximum_inspection_at TIMESTAMP WITH TIME ZONE NOT NULL,
  reason_code VARCHAR(80) NOT NULL,
  explanation VARCHAR(1000) NOT NULL,
  rules_version VARCHAR(80) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT recommendations_owned_observation_foreign_key
    FOREIGN KEY (observation_id, plant_id, owner_id)
    REFERENCES soil_observations (id, plant_id, owner_id),
  CONSTRAINT recommendations_date_order_check CHECK (
    minimum_inspection_at <= recommended_inspection_at
    AND recommended_inspection_at <= maximum_inspection_at
  )
);

CREATE INDEX recommendations_plant_owner_index
  ON inspection_recommendations (plant_id, owner_id, created_at);
