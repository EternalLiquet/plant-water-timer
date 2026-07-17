package com.eternalliquet.plantcare.plants;

import java.util.UUID;

@FunctionalInterface
public interface IdentifierGenerator {
  UUID next();
}
