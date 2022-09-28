package org.opentripplanner.standalone.config.framework;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.EnumSet;
import javax.annotation.Nonnull;
import org.opentripplanner.util.time.DurationUtils;

/**
 * These are the types we support in the NodeAdaptor
 */
public enum ConfigType {
  BOOLEAN(JsonType.basic, "This is the Boolean JSON type", "true or false"),
  STRING(JsonType.string, "This is the String JSON type.", "'This is a string!'"),
  DOUBLE(JsonType.basic, "A decimal floating point _number_. 64 bit.", "3.15"),
  INTEGER(JsonType.basic, "A decimal integer _number_. 32 bit.", "1, -7, 2100200300"),
  LONG(JsonType.basic, "A decimal integer _number_. 64 bit.", "-1234567890123456789"),
  ENUM(JsonType.string, "A fixed set of string literals.", "BicycleOptimize: QUICK, SAFE..."),
  ENUM_MAP(
    JsonType.object,
    "List of key/value pairs, where the key is a enum and the value can be any given type.",
    "{ RAIL: 1.2, BUS: 2.3 }"
  ),
  ENUM_SET(JsonType.object, "List of enum string values", "[ RAIL, TRAM ]"),
  LOCALE(
    JsonType.string,
    "_`Language[\\_country[\\_variant]]`_. A Locale object represents a specific geographical, political, or cultural region. For more information see the [Java 11 Locale](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/Locale.html).",
    "en_US, nn_NO"
  ),
  DATE(JsonType.string, "Local date. The format is _YYYY-MM-DD_ (ISO-8601).", "2020-09-21"),
  DATE_OR_PERIOD(
    JsonType.string,
    "A _local date_, or a _period_ relative to today. The local date has the format `YYYY-MM-DD` and the period has the format `PnYnMnD` or `-PnYnMnD` where `n` is a integer number.",
    "P1Y, -P3M2D, P1D"
  ),
  DURATION(
    JsonType.string,
    "A _duration_ is a amount of time. The format is `PnDTnHnMnS` or `nDnHnMnS` where `n` is a  integer number. The `D`(days), `H`(hours), `M`(minutes) and `S`(seconds) are not case sensitive.",
    "3h, 2m, 1d5h2m3s, -P2dT-1s, P-2dT1s"
  ),
  REGEXP(
    JsonType.string,
    "A regular expression pattern used to match a sting.",
    "'$^', 'gtfs', '$\\w{3})-.*\\.xml^'"
  ),
  URI(
    JsonType.string,
    "An URI path to a resource like a file or a URL. Relative URIs are resolved relative to the OTP base path.",
    "'gs://bucket/path/a.obj', 'http://foo.bar/', 'file:///Users/x/local/file' 'myGraph.obj', '../street/streetGraph-${otp.serialization.version.id}.obj'"
  ),
  ZONE_ID(JsonType.string, "TODO", "TODO"),
  FEED_SCOPED_ID(JsonType.string, "FeedScopedId", "FEED_ID:1001"),
  LINEAR_FUNCTION(
    JsonType.string,
    "A linear function with one input parameter(x) used to calculate a value. Usually used to calculate a limit. For example to calculate a limit in seconds to be 1 hour plus 2 times the value(x) use: `3600 + 2.0 x`, to set an absolute value(3000) use: `3000 + 0x`",
    "'600 + 2.0 x'"
  ),
  MAP(
    JsonType.object,
    "List of key/value pairs, where the key is a string and the value can be any given type.",
    "{ 'one': 1.2, 'two': 2.3 }"
  ),
  OBJECT(
    JsonType.object,
    "Config object containing nested elements",
    "'walk': { 'speed': 1.3, 'reluctance': 5 }"
  ),
  ARRAY(
    JsonType.array,
    "Config object containing an array/list of elements",
    "'array': [ 1, 2, 3 ]"
  );

  private final JsonType type;
  private final String description;
  private final String examples;

  ConfigType(JsonType type, String description, String examples) {
    this.type = type;
    this.description = description;
    this.examples = examples.replace('\'', '\"');
  }

  public String description() {
    return description;
  }

  public String examples() {
    return examples.replace('\'', '\"');
  }

  public String docName() {
    return name().toLowerCase();
  }

  public String wrap(@Nonnull Object value) {
    return type == JsonType.string ? "\"" + value + "\"" : value.toString();
  }

  public boolean isComplex() {
    return type == JsonType.object || type == JsonType.array;
  }

  public boolean isMapOrArray() {
    return EnumSet.of(ARRAY, MAP, ENUM_MAP).contains(this);
  }

  /** Internal to this class */
  private enum JsonType {
    basic,
    string,
    object,
    array,
  }

  static ConfigType of(Class<?> javaType) {
    if (Boolean.class.isAssignableFrom(javaType)) {
      return BOOLEAN;
    }
    if (Double.class.isAssignableFrom(javaType)) {
      return DOUBLE;
    }
    if (Duration.class.isAssignableFrom(javaType)) {
      return DURATION;
    }
    if (Integer.class.isAssignableFrom(javaType)) {
      return INTEGER;
    }
    if (Long.class.isAssignableFrom(javaType)) {
      return LONG;
    }
    if (String.class.isAssignableFrom(javaType)) {
      return STRING;
    }
    throw new IllegalArgumentException("Type not supported: " + javaType);
  }

  @SuppressWarnings("unchecked")
  static <T> T getParameter(ConfigType elementType, JsonNode node) {
    return switch (elementType) {
      case BOOLEAN -> (T) (Boolean) node.asBoolean();
      case DOUBLE -> (T) (Double) node.asDouble();
      case INTEGER -> (T) (Integer) node.asInt();
      case LONG -> (T) (Long) node.asLong();
      case STRING -> (T) (String) node.asText();
      case DURATION -> (T) DurationUtils.duration(node.asText());
      default -> throw new IllegalArgumentException("Unsupported element type: " + elementType);
    };
  }
}
