package org.opentripplanner.graph_builder.module.osm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.opentripplanner.graph_builder.module.osm.specifier.BestMatchSpecifier;

class MixinPropertiesBuilderTest {

  @Test
  void walkSafety() {
    var b = MixinPropertiesBuilder.ofWalkSafety(5).build(new BestMatchSpecifier("foo=bar"));

    assertEquals(5, b.walkSafety().forward());
    assertEquals(5, b.walkSafety().back());
    assertEquals(1, b.bicycleSafety().forward());
    assertEquals(1, b.bicycleSafety().back());
  }

  @Test
  void bikeSafety() {
    var b = MixinPropertiesBuilder.ofBicycleSafety(5).build(new BestMatchSpecifier("foo=bar"));

    assertEquals(5, b.bicycleSafety().forward());
    assertEquals(5, b.bicycleSafety().back());
    assertEquals(1, b.walkSafety().forward());
    assertEquals(1, b.walkSafety().back());
  }
}
