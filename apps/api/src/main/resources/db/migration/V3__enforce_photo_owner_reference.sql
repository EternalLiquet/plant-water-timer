ALTER TABLE garden_photos ADD CONSTRAINT garden_photos_owner_reference UNIQUE (id, owner_id);
ALTER TABLE plants ADD CONSTRAINT plants_owned_photo FOREIGN KEY (photo_id, owner_id)
  REFERENCES garden_photos(id, owner_id);
