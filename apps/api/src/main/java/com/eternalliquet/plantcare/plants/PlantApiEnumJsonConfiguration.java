package com.eternalliquet.plantcare.plants;

import com.eternalliquet.plantcare.inspection.SoilState;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class PlantApiEnumJsonConfiguration {

  @Bean
  SimpleModule plantApiEnumModule() {
    var module = new SimpleModule("plant-api-enum-contract");
    register(
        module,
        PlantEnvironment.class,
        Map.of("indoor", PlantEnvironment.INDOOR, "outdoor", PlantEnvironment.OUTDOOR));
    register(
        module,
        PotMaterial.class,
        Map.of(
            "plastic", PotMaterial.PLASTIC,
            "ceramic", PotMaterial.CERAMIC,
            "terracotta", PotMaterial.TERRACOTTA,
            "unknown", PotMaterial.UNKNOWN));
    register(
        module,
        Drainage.class,
        Map.of("yes", Drainage.YES, "no", Drainage.NO, "unknown", Drainage.UNKNOWN));
    register(
        module,
        LightLevel.class,
        Map.of(
            "low", LightLevel.LOW,
            "medium_indirect", LightLevel.MEDIUM_INDIRECT,
            "bright_indirect", LightLevel.BRIGHT_INDIRECT,
            "direct", LightLevel.DIRECT,
            "unknown", LightLevel.UNKNOWN));
    register(
        module,
        SoilState.class,
        Map.of("wet", SoilState.WET, "moist", SoilState.MOIST, "dry", SoilState.DRY));
    return module;
  }

  private static <E extends Enum<E>> void register(
      SimpleModule module, Class<E> enumType, Map<String, E> byExternalValue) {
    var byEnumValue = new EnumMap<E, String>(enumType);
    byExternalValue.forEach((externalValue, enumValue) -> byEnumValue.put(enumValue, externalValue));
    if (byEnumValue.size() != enumType.getEnumConstants().length) {
      throw new IllegalStateException("Every " + enumType.getSimpleName() + " value needs a JSON mapping");
    }

    module.addSerializer(
        enumType,
        new JsonSerializer<>() {
          @Override
          public void serialize(E value, JsonGenerator generator, SerializerProvider serializers)
              throws IOException {
            generator.writeString(byEnumValue.get(value));
          }
        });
    module.addDeserializer(
        enumType,
        new JsonDeserializer<>() {
          @Override
          public E deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            var externalValue = parser.getValueAsString();
            var enumValue = byExternalValue.get(externalValue);
            if (enumValue == null) {
              throw InvalidFormatException.from(
                  parser,
                  "Expected a documented " + enumType.getSimpleName() + " value",
                  externalValue,
                  enumType);
            }
            return enumValue;
          }
        });
  }
}
