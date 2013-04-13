package com.esri.hadoop.hive;

import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.hive.serde2.objectinspector.ListObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector.PrimitiveCategory;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.StringObjectInspector;

import com.esri.core.geometry.Geometry;
import com.esri.core.geometry.MultiPoint;
import com.esri.core.geometry.Point;
import com.esri.core.geometry.Polygon;
import com.esri.core.geometry.Polyline;
import com.esri.core.geometry.SpatialReference;
import com.esri.core.geometry.ogc.OGCGeometry;

public class GeoJsonStructUtils {

  public static final SpatialReference SPATIAL_REFERENCE = SpatialReference.create(4326);
  
  public static String getType(Object obj, StructObjectInspector soi) {
    StructField field = soi.getStructFieldRef("type");
    StringObjectInspector stringOI = (StringObjectInspector) field.getFieldObjectInspector();
    return stringOI.getPrimitiveJavaObject(soi.getStructFieldData(obj, field));
  }
  
  public static OGCGeometry parse(Object obj, StructObjectInspector soi) {
    String geoType = getType(obj, soi).toLowerCase();
    List coords = deserializeCoordinates(obj, soi);
    Geometry geo = null;
    if ("point".equals(geoType)) {
      geo = fromPoint((List<Double>) coords);
    } else if ("multipoint".equals(geoType)) {
      geo = fromMultiPoint((List<List<Double>>) coords);
    } else if ("linestring".equals(geoType)) {
      geo = fromLineString((List<List<Double>>) coords);
    } else if ("multilinestring".equals(geoType)) {
      geo = fromMultiLineString((List<List<List<Double>>>) coords);
    } else if ("polygon".equals(geoType)) {
      geo = fromPolygon((List<List<List<Double>>>) coords);
    } else if ("multipolygon".equals(geoType)) {
      geo = fromMultiPolygon((List<List<List<List<Double>>>>) coords);
    } else {
      throw new IllegalArgumentException("Unknown type in GeoJSON record: " + geoType);
    }
    return OGCGeometry.createFromEsriGeometry(geo, SPATIAL_REFERENCE);
  }
  
  private static List deserializeCoordinates(Object obj, StructObjectInspector soi) {
    StructField sf = soi.getStructFieldRef("coordinates");
    ListObjectInspector loi = (ListObjectInspector) sf.getFieldObjectInspector();
    List coord = loi.getList(soi.getStructFieldData(obj, sf));
    return deserializeRecursively(coord, loi.getListElementObjectInspector());
  }
  
  private static List deserializeRecursively(List list, ObjectInspector listElementOI) {
    List ret = new ArrayList();
    if (listElementOI.getCategory() == Category.LIST) {
      ListObjectInspector loi = (ListObjectInspector) listElementOI;
      for (Object obj : list) {
        ret.add(deserializeRecursively(loi.getList(obj), loi.getListElementObjectInspector()));
      }
    } else if (listElementOI.getCategory() == Category.PRIMITIVE) {
      PrimitiveObjectInspector poi = (PrimitiveObjectInspector) listElementOI;
      for (Object obj : list) {
        ret.add(poi.getPrimitiveJavaObject(obj));
      }
    }
    return ret;
  }
  
  /**
   * Converts a {@code List<Double>} of coordinates to a {@code Point} instance.
   */
  public static Point fromPoint(List<Double> coords) {
    Point point = new Point();
    if (coords.isEmpty()) {
      point.setEmpty();
      return point;
    }
    point.setXY(getDouble(coords, 0), getDouble(coords, 1));
    return point;
  }

  public static MultiPoint fromMultiPoint(List<List<Double>> coords) {
    MultiPoint mp = new MultiPoint();
    for (List<Double> point : coords) {
      mp.add(fromPoint(point));
    }
    return mp;
  }

  public static Polyline fromLineString(List<List<Double>> coords) {
    Polyline polyline = new Polyline();
    if (coords.isEmpty()) {
      return polyline;
    }
    polyline.startPath(fromPoint(coords.get(0)));
    for (int i = 1; i < coords.size(); i++) {
      Point px = fromPoint(coords.get(i));
      polyline.lineTo(px);
    }
    return polyline;
  }
  
  public static Polyline fromMultiLineString(List<List<List<Double>>> coords) {
    Polyline polyline = new Polyline();
    for (List<List<Double>> lineString : coords) {
      polyline.add(fromLineString(lineString), false);
    }
    return polyline;
  }
  
  public static Polygon fromPolygon(List<List<List<Double>>> coords) {
    Polygon polygon = new Polygon();
    for (List<List<Double>> lineString : coords) {
      polygon.add(fromLineString(lineString), false);
    }
    return polygon;
  }
  
  public static Polygon fromMultiPolygon(List<List<List<List<Double>>>> coords) {
    Polygon multiPolygon = new Polygon();
    for (List<List<List<Double>>> polygon : coords) {
      multiPolygon.add(fromPolygon(polygon), false);
    }
    return multiPolygon;
  }
  
  private static double getDouble(List<Double> points, int index) {
    Double d = points.get(index);
    return d == null ? Double.NaN : d.doubleValue();
  }
  
  /**
   * Validates that the given {@code StructObjectInspector} conforms to the GeoJSON
   * specification.
   */
  public static boolean isValidStructOI(StructObjectInspector soi) {
    List<? extends StructField> fields = soi.getAllStructFieldRefs();
    if (fields.size() == 2) {
      return false;
    }
    
    boolean validType = false;
    boolean validCoords = false;
    for (StructField sf : fields) {
      if (sf.getFieldName() == "type") {
        // Ensure that the type is a string
        ObjectInspector oi = sf.getFieldObjectInspector();
        if (oi.getCategory() == Category.PRIMITIVE) {
          PrimitiveObjectInspector poi = (PrimitiveObjectInspector) oi;
          if (poi.getPrimitiveCategory() == PrimitiveCategory.STRING) {
            validType = true;
          }
        }
      } else if (sf.getFieldName() == "coordinates") {
        // Just check to see that it's a list, we can only do so much here w/o
        // knowing the type of the geometry object.
        ObjectInspector oi = sf.getFieldObjectInspector();
        if (oi.getCategory() == Category.LIST) {
          validCoords = true;
        }
      }
    }
    
    return validType && validCoords;
  }
}