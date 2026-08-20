package com.rockmap.app.field;

import com.rockmap.app.waypoints.WaypointEntity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class FieldImport {
    public static final int MAX_POINTS = 20_000;

    public static final class ImportedTrack {
        public final String name;
        public final List<GeoMath.Point> points;
        ImportedTrack(String name, List<GeoMath.Point> points){this.name=name;this.points=points;}
    }
    public static final class ImportedArea {
        public final String name;
        public final List<GeoMath.Point> points;
        ImportedArea(String name, List<GeoMath.Point> points){this.name=name;this.points=points;}
    }
    public static final class Result {
        public final List<WaypointEntity> waypoints=new ArrayList<>();
        public final List<ImportedTrack> tracks=new ArrayList<>();
        public final List<ImportedArea> areas=new ArrayList<>();
        public int pointCount;
    }

    private FieldImport(){}

    public static Result parse(byte[] bytes, String displayName) throws Exception {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Selected file is empty.");
        String text=new String(bytes, StandardCharsets.UTF_8).trim();
        String lower=displayName==null?"":displayName.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".gpx") || text.startsWith("<gpx") || text.contains("<gpx ")) return parseGpx(bytes);
        if (lower.endsWith(".kml") || text.contains("<kml")) return parseKml(bytes);
        if (lower.endsWith(".geojson") || lower.endsWith(".json") || text.startsWith("{")) return parseGeoJson(text);
        throw new IllegalArgumentException("Supported imports are GPX, KML and GeoJSON.");
    }

    private static Result parseGpx(byte[] bytes) throws Exception {
        Result result=new Result();
        XmlPullParser parser=parser(bytes);
        ArrayList<GeoMath.Point> currentTrack=null;
        ArrayList<GeoMath.Point> currentRoute=null;
        String currentName="";
        Double waypointLat=null, waypointLon=null;
        long now=System.currentTimeMillis();
        while (parser.getEventType()!=XmlPullParser.END_DOCUMENT) {
            int event=parser.getEventType();
            if (event==XmlPullParser.START_TAG) {
                String tag=parser.getName();
                if ("trk".equals(tag)) { currentTrack=new ArrayList<>(); currentName=""; }
                else if ("rte".equals(tag)) { currentRoute=new ArrayList<>(); currentName=""; }
                else if ("wpt".equals(tag)) {
                    waypointLat=number(parser.getAttributeValue(null,"lat"));
                    waypointLon=number(parser.getAttributeValue(null,"lon"));
                    currentName="";
                } else if ("name".equals(tag)) {
                    currentName=parser.nextText().trim();
                } else if ("trkpt".equals(tag) && currentTrack!=null) {
                    currentTrack.add(new GeoMath.Point(number(parser.getAttributeValue(null,"lat")), number(parser.getAttributeValue(null,"lon"))));
                    count(result,1);
                } else if ("rtept".equals(tag) && currentRoute!=null) {
                    currentRoute.add(new GeoMath.Point(number(parser.getAttributeValue(null,"lat")), number(parser.getAttributeValue(null,"lon"))));
                    count(result,1);
                }
            } else if (event==XmlPullParser.END_TAG) {
                String tag=parser.getName();
                if ("wpt".equals(tag) && waypointLat!=null && waypointLon!=null) {
                    String name=currentName.isEmpty()?"Imported GPX waypoint":currentName;
                    result.waypoints.add(new WaypointEntity(waypointLat,waypointLon,-1f,now,name,"Imported from GPX",now,now));
                    count(result,1); waypointLat=null; waypointLon=null; currentName="";
                } else if ("trk".equals(tag) && currentTrack!=null) {
                    if (currentTrack.size()>=2) result.tracks.add(new ImportedTrack(currentName.isEmpty()?"Imported GPX track":currentName,currentTrack));
                    currentTrack=null; currentName="";
                } else if ("rte".equals(tag) && currentRoute!=null) {
                    if (currentRoute.size()>=2) result.tracks.add(new ImportedTrack(currentName.isEmpty()?"Imported GPX route":currentName,currentRoute));
                    currentRoute=null; currentName="";
                }
            }
            parser.next();
        }
        if (result.waypoints.isEmpty() && result.tracks.isEmpty()) throw new IllegalArgumentException("No usable GPX waypoints, routes or tracks were found.");
        return result;
    }

    private static Result parseKml(byte[] bytes) throws Exception {
        Result result=new Result();
        XmlPullParser parser=parser(bytes);
        String name="Imported KML item";
        String geometry="";
        long now=System.currentTimeMillis();
        while (parser.getEventType()!=XmlPullParser.END_DOCUMENT) {
            if (parser.getEventType()==XmlPullParser.START_TAG) {
                String tag=parser.getName();
                if ("Placemark".equals(tag)) { name="Imported KML item"; geometry=""; }
                else if ("name".equals(tag)) name=parser.nextText().trim();
                else if ("Point".equals(tag) || "LineString".equals(tag) || "Polygon".equals(tag)) geometry=tag;
                else if ("coordinates".equals(tag)) {
                    List<GeoMath.Point> points=parseKmlCoordinates(parser.nextText());
                    count(result,points.size());
                    if ("Point".equals(geometry) && !points.isEmpty()) {
                        GeoMath.Point p=points.get(0);
                        result.waypoints.add(new WaypointEntity(p.lat,p.lon,-1f,now,name,"Imported from KML",now,now));
                    } else if ("LineString".equals(geometry) && points.size()>=2) result.tracks.add(new ImportedTrack(name,points));
                    else if ("Polygon".equals(geometry) && points.size()>=3) result.areas.add(new ImportedArea(name,stripClosingPoint(points)));
                }
            }
            parser.next();
        }
        if (result.waypoints.isEmpty() && result.tracks.isEmpty() && result.areas.isEmpty()) throw new IllegalArgumentException("No usable KML geometry was found.");
        return result;
    }

    private static Result parseGeoJson(String text) throws JSONException {
        Result result=new Result();
        JSONObject root=new JSONObject(text);
        if ("FeatureCollection".equals(root.optString("type"))) {
            JSONArray features=root.optJSONArray("features");
            if (features!=null) for(int i=0;i<features.length();i++) parseFeature(features.optJSONObject(i),result);
        } else if ("Feature".equals(root.optString("type"))) parseFeature(root,result);
        else throw new JSONException("Expected GeoJSON Feature or FeatureCollection.");
        if (result.waypoints.isEmpty() && result.tracks.isEmpty() && result.areas.isEmpty()) throw new JSONException("No usable GeoJSON points, lines or polygons were found.");
        return result;
    }

    private static void parseFeature(JSONObject feature, Result result) throws JSONException {
        if (feature==null) return;
        JSONObject geometry=feature.optJSONObject("geometry"); if (geometry==null) return;
        JSONObject properties=feature.optJSONObject("properties");
        String name=properties==null?"Imported item":properties.optString("name","Imported item");
        String notes=properties==null?"":properties.optString("notes","");
        String type=geometry.optString("type");
        JSONArray coords=geometry.optJSONArray("coordinates"); if(coords==null)return;
        long now=System.currentTimeMillis();
        if ("Point".equals(type) && coords.length()>=2) {
            result.waypoints.add(new WaypointEntity(coords.getDouble(1),coords.getDouble(0),-1f,now,name,notes,now,now)); count(result,1);
        } else if ("LineString".equals(type)) {
            List<GeoMath.Point> points=jsonPoints(coords); count(result,points.size());
            if(points.size()>=2)result.tracks.add(new ImportedTrack(name,points));
        } else if ("Polygon".equals(type) && coords.length()>0) {
            List<GeoMath.Point> points=jsonPoints(coords.getJSONArray(0)); count(result,points.size());
            if(points.size()>=3)result.areas.add(new ImportedArea(name,stripClosingPoint(points)));
        }
    }

    private static List<GeoMath.Point> jsonPoints(JSONArray coords) throws JSONException {
        ArrayList<GeoMath.Point> out=new ArrayList<>();
        for(int i=0;i<coords.length();i++){JSONArray p=coords.getJSONArray(i); if(p.length()>=2)out.add(new GeoMath.Point(p.getDouble(1),p.getDouble(0)));}
        return out;
    }

    private static List<GeoMath.Point> parseKmlCoordinates(String raw) {
        ArrayList<GeoMath.Point> out=new ArrayList<>();
        for(String token:raw.trim().split("\\s+")){
            String[] p=token.split(",");
            if(p.length>=2){try{out.add(new GeoMath.Point(Double.parseDouble(p[1]),Double.parseDouble(p[0])));}catch(RuntimeException ignored){}}
        }
        return out;
    }

    private static List<GeoMath.Point> stripClosingPoint(List<GeoMath.Point> points){
        if(points.size()>3){GeoMath.Point a=points.get(0),b=points.get(points.size()-1);if(Math.abs(a.lat-b.lat)<1e-10&&Math.abs(a.lon-b.lon)<1e-10)return new ArrayList<>(points.subList(0,points.size()-1));}
        return points;
    }

    private static XmlPullParser parser(byte[] bytes) throws Exception {
        XmlPullParserFactory factory=XmlPullParserFactory.newInstance(); factory.setNamespaceAware(false);
        XmlPullParser parser=factory.newPullParser(); parser.setInput(new ByteArrayInputStream(bytes),"UTF-8"); return parser;
    }

    private static double number(String text){if(text==null)throw new IllegalArgumentException("Missing coordinate.");return Double.parseDouble(text);}
    private static void count(Result r,int count){r.pointCount+=count;if(r.pointCount>MAX_POINTS)throw new IllegalArgumentException("Import exceeds the 20,000-point safety limit.");}
}
