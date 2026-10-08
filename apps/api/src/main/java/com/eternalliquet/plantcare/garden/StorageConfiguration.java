package com.eternalliquet.plantcare.garden;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class StorageConfiguration {
  @Bean
  static BeanFactoryPostProcessor privateStorageBeforeDatabase() {
    return factory -> {
      if (factory.containsBeanDefinition("dataSource")) {
        var definition = factory.getBeanDefinition("dataSource");
        var dependencies = new ArrayList<String>();
        if (definition.getDependsOn() != null)
          dependencies.addAll(Arrays.asList(definition.getDependsOn()));
        dependencies.add("gardenStorageSafety");
        definition.setDependsOn(dependencies.toArray(String[]::new));
      }
    };
  }

  @Bean
  String gardenStorageSafety(@Value("${spring.datasource.url:}") String url) throws IOException {
    if (url.startsWith("jdbc:h2:file:")) {
      String file = url.substring("jdbc:h2:file:".length()).split(";", 2)[0];
      if (file.startsWith("~"))
        throw new IllegalStateException(
            "Use an absolute private H2 file path instead of a home-directory shorthand.");
      PrivateStorage.ensureDirectory(Path.of(file).toAbsolutePath().normalize().getParent());
    }
    return "private-storage-checked";
  }
}
