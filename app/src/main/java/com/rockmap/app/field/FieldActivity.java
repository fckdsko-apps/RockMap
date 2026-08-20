package com.rockmap.app.field;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.rockmap.app.coordinates.CoordinateParser;
import com.rockmap.app.location.LocationRepository;
import com.rockmap.app.waypoints.WaypointEntity;
import com.rockmap.app.waypoints.WaypointRepository;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class FieldActivity extends Activity implements LocationRepository.Listener {
    private static final int REQ_LOCATION = 811;
    private static final int REQ_IMPORT = 812;
    private static final int REQ_PHOTO = 813;
    private static final int MAX_IMPORT_BYTES = 10 * 1024 * 1024;

    private FieldDatabase db;
    private WaypointRepository waypointRepository;
    private LocationRepository locationRepository;
    private Runnable pendingLocationAction;
    private String pendingPhotoUri = "";
    private boolean started;
    private GeoMath.Point navigationTarget;
    private TextView navigationStatus;
    private String navigationTitle = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = FieldDatabase.get(this);
        waypointRepository = new WaypointRepository(this);
        locationRepository = new LocationRepository(this, this);
        showHub();
    }

    private void showHub() {
        setTitle("RockMap Field");
        LinearLayout root = page();
        root.addView(title("Field"));
        root.addView(help("Record where you went, keep richer observations and samples, measure ground, import existing GPS/GIS files, and convert coordinates. Core map/GPS behavior is unchanged."));

        root.addView(action("Track recording & backtrack", "Record a GPS breadcrumb trail, pause/resume, review distance and backtrack to the start.", v -> showTracks()));
        root.addView(action("Field records & samples", "Richer saved observations with category, mineral, sample ID, notes, photo, GPS accuracy and elevation.", v -> showFieldRecords()));
        root.addView(action("Saved locations", "View the existing RockMap saved-marker database or copy a marker into a richer field record.", v -> showLegacyWaypoints()));
        root.addView(action("Measure & prospecting areas", "Measure distance, bearing and polygon area from coordinates; save reusable areas locally.", v -> showMeasure()));
        root.addView(action("Import GPX / KML / GeoJSON", "Add waypoints, tracks and polygon areas. Imports are additive and never delete existing RockMap data.", v -> beginImport()));
        root.addView(action("Coordinate formats", "Convert one location between decimal degrees, DDM, DMS, WGS84 UTM and MGRS.", v -> showCoordinates()));
        root.addView(action("Back to map", "Return to the normal RockMap map screen.", v -> finish()));
        setContentView(scroll(root));
    }

    // ---------- TRACKS ----------

    private void showTracks() {
        LinearLayout root=page();
        root.addView(title("Tracks"));
        FieldDatabase.Track active=db.getActiveTrack();
        if(active==null){
            root.addView(help("Track recording uses the GPS provider and an Android foreground service. RockMap does not request background-location permission. Android may show the recording indicator in the notification drawer or foreground-services task UI, depending on your notification settings."));
            root.addView(action("Start new track", "Begins recording after a precise-location check.", v->startNewTrack()));
        }else{
            List<GeoMath.Point> points=db.getTrackPoints(active.id);
            root.addView(help(active.name+"\n"+trackStatus(active,points)));
            LinearLayout row=row();
            if(FieldDatabase.TRACK_PAUSED.equals(active.status)) row.addView(small("Resume",v->trackCommand(TrackRecordingService.ACTION_RESUME,active.id)),weight());
            else row.addView(small("Pause",v->trackCommand(TrackRecordingService.ACTION_PAUSE,active.id)),weight());
            row.addView(small("Stop",v->confirmStopTrack(active)),weight());
            root.addView(row);
            root.addView(action("Open active track", "View breadcrumb shape, stats and backtrack guidance.", v->showTrackDetail(active.id)));
        }
        root.addView(section("Recent tracks"));
        List<FieldDatabase.Track> tracks=db.listTracks(50);
        if(tracks.isEmpty())root.addView(help("No recorded or imported tracks yet."));
        else for(FieldDatabase.Track track:tracks){
            List<GeoMath.Point> pts=db.getTrackPoints(track.id);
            root.addView(action(track.name,trackStatus(track,pts),v->showTrackDetail(track.id)));
        }
        root.addView(back()); setContentView(scroll(root));
    }

    private void startNewTrack(){
        runWithPreciseLocation(()->{
            EditText input=new EditText(this); input.setHint("Track name"); input.setText("Field track — "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date())); input.setSingleLine(true);
            new AlertDialog.Builder(this).setTitle("Start track recording").setMessage("Recording can continue while you use the RockMap map or lock the screen. Android keeps a foreground-service indicator active until you stop the track; where it appears depends on your notification settings.").setView(input)
                    .setPositiveButton("Start",(d,w)->{
                        // Permission can be changed while this confirmation dialog is open.
                        // Re-check at the exact point where the location foreground service starts.
                        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
                            toast("Precise location is required to start track recording.");
                            return;
                        }
                        long id=db.createTrack(input.getText().toString().trim(),System.currentTimeMillis());
                        Intent service=new Intent(this,TrackRecordingService.class).setAction(TrackRecordingService.ACTION_START).putExtra(TrackRecordingService.EXTRA_TRACK_ID,id);
                        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)startForegroundService(service); else startService(service);
                        showTracks();
                    }).setNegativeButton("Cancel",null).show();
        });
    }

    private void trackCommand(String action,long id){
        Intent service=new Intent(this,TrackRecordingService.class).setAction(action).putExtra(TrackRecordingService.EXTRA_TRACK_ID,id);
        startService(service); getWindow().getDecorView().postDelayed(this::showTracks,250);
    }

    private void confirmStopTrack(FieldDatabase.Track track){
        new AlertDialog.Builder(this).setTitle("Stop this track?").setMessage("The recorded breadcrumb points stay on this device and can still be reviewed or used for backtrack.")
                .setPositiveButton("Stop",(d,w)->trackCommand(TrackRecordingService.ACTION_STOP,track.id)).setNegativeButton("Cancel",null).show();
    }

    private String trackStatus(FieldDatabase.Track track,List<GeoMath.Point> pts){
        double meters=GeoMath.pathDistanceMeters(pts);
        long end=track.endedAt>0?track.endedAt:System.currentTimeMillis();
        long duration=Math.max(0,end-track.startedAt);
        return (track.status==null?"":track.status)+" · "+pts.size()+" points · "+GeoMath.distanceLabel(meters)+" · "+durationLabel(duration);
    }

    private void showTrackDetail(long trackId){
        FieldDatabase.Track track=db.getTrack(trackId); if(track==null){toast("Track not found.");showTracks();return;}
        List<GeoMath.Point> pts=db.getTrackPoints(trackId);
        LinearLayout root=page(); root.addView(title(track.name)); root.addView(help(trackStatus(track,pts)+"\nStarted: "+DateFormat.getDateTimeInstance().format(new Date(track.startedAt))));
        BreadcrumbView breadcrumb=new BreadcrumbView(this); breadcrumb.setPoints(pts); root.addView(breadcrumb,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(250)));
        if(pts.size()>=2)root.addView(action("Backtrack", "Live distance/bearing to the recorded start plus the breadcrumb shape.",v->showBacktrack(trackId)));
        Button delete=button("Delete track"); delete.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Delete track?").setMessage("This permanently removes the track and its breadcrumb points from RockMap Field.").setPositiveButton("Delete",(d,w)->{db.deleteTrack(trackId);showTracks();}).setNegativeButton("Cancel",null).show()); root.addView(delete);
        root.addView(nav("Back to tracks",v->showTracks())); setContentView(scroll(root));
    }

    private void showBacktrack(long trackId){
        List<GeoMath.Point> pts=db.getTrackPoints(trackId); if(pts.size()<2){toast("Track has too few points for backtrack.");return;}
        LinearLayout root=page(); root.addView(title("Backtrack"));
        TextView status=help("Getting a fresh GPS fix…"); root.addView(status);
        BreadcrumbView view=new BreadcrumbView(this); view.setPoints(pts); root.addView(view,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(280)));
        root.addView(help("Green = recorded start · red = recorded end · blue = current GPS. This is simple bearing guidance, not turn-by-turn routing. Terrain, access and hazards are not evaluated."));
        root.addView(nav("Back to track",v->showTrackDetail(trackId))); setContentView(scroll(root));
        runWithPreciseLocation(()->locationRepository.requestFreshPrecise(location->{
            GeoMath.Point current=point(location); view.setCurrent(current); GeoMath.Point start=pts.get(0);
            double distance=GeoMath.distanceMeters(current,start); double bearing=GeoMath.initialBearingDegrees(current,start);
            status.setText("To recorded start: "+GeoMath.distanceLabel(distance)+" · "+String.format(Locale.US,"%.0f° %s",bearing,GeoMath.cardinal(bearing))+"\nCurrent: "+current.decimal());
        },this::toast));
    }

    // ---------- FIELD RECORDS ----------

    private void showFieldRecords(){
        LinearLayout root=page(); root.addView(title("Field records & samples"));
        LinearLayout add=row(); add.addView(small("New at GPS",v->newFieldAtGps()),weight()); add.addView(small("New at coordinates",v->newFieldAtCoordinates()),weight()); root.addView(add);
        List<FieldDatabase.FieldRecord> records=db.listFieldRecords();
        if(records.isEmpty())root.addView(help("No field records yet."));
        else for(FieldDatabase.FieldRecord r:records){
            String detail=(r.category==null||r.category.isEmpty()?"Field record":r.category)+(r.mineral==null||r.mineral.isEmpty()?"":" · "+r.mineral)+(r.sampleId==null||r.sampleId.isEmpty()?"":" · Sample "+r.sampleId)+"\n"+CoordinateFormats.decimal(r.lat,r.lon);
            root.addView(action(r.name,detail,v->showFieldRecord(r.id)));
        }
        root.addView(back()); setContentView(scroll(root));
    }

    private void newFieldAtGps(){runWithPreciseLocation(()->locationRepository.requestFreshPrecise(l->editFieldRecord(new FieldDatabase.FieldRecord(0,"","","","","",l.getLatitude(),l.getLongitude(),l.hasAltitude()?l.getAltitude():Double.NaN,l.hasAccuracy()?l.getAccuracy():-1f,"",System.currentTimeMillis(),System.currentTimeMillis())),this::toast));}
    private void newFieldAtCoordinates(){
        EditText input=new EditText(this);input.setHint("Latitude, longitude");input.setSingleLine(true);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("New field record at coordinates").setView(input).setPositiveButton("Continue",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{CoordinateParser.Result r=CoordinateParser.parse(input.getText().toString());dialog.dismiss();editFieldRecord(new FieldDatabase.FieldRecord(0,"","","","","",r.latitude,r.longitude,Double.NaN,-1f,"",System.currentTimeMillis(),System.currentTimeMillis()));}catch(IllegalArgumentException ex){input.setError(ex.getMessage());}})); dialog.show();
    }

    private void editFieldRecord(FieldDatabase.FieldRecord record){
        pendingPhotoUri=record.photoUri==null?"":record.photoUri;
        LinearLayout box=page();
        EditText name=input("Name",record.name); EditText category=input("Category (outcrop, float, mine, dump, vein…)",record.category); EditText mineral=input("Mineral / material",record.mineral); EditText sample=input("Sample ID (optional)",record.sampleId); EditText notes=input("Notes",record.notes); notes.setMinLines(4); notes.setSingleLine(false);
        box.addView(name);box.addView(category);box.addView(mineral);box.addView(sample);box.addView(notes);
        TextView coords=help(CoordinateFormats.decimal(record.lat,record.lon)+(Double.isFinite(record.altitude)?String.format(Locale.US,"\nElevation: %.1f m",record.altitude):"")+(record.accuracy>=0?String.format(Locale.US,"\nGPS accuracy: ±%.1f m",record.accuracy):""));box.addView(coords);
        Button photo=button(pendingPhotoUri.isEmpty()?"Attach photo":"Change attached photo"); photo.setOnClickListener(v->beginPhotoPick()); box.addView(photo);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(record.id>0?"Edit field record":"New field record").setView(scroll(box)).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String label=name.getText().toString().trim();if(label.isEmpty()){name.setError("Enter a name.");return;}record.name=bounded(label,500);record.category=bounded(category.getText().toString().trim(),300);record.mineral=bounded(mineral.getText().toString().trim(),500);record.sampleId=bounded(sample.getText().toString().trim(),200);record.notes=bounded(notes.getText().toString().trim(),20000);record.photoUri=pendingPhotoUri;record.updatedAt=System.currentTimeMillis();if(record.createdAt<=0)record.createdAt=record.updatedAt;if(record.id>0)db.updateFieldRecord(record);else record.id=db.insertFieldRecord(record);dialog.dismiss();showFieldRecord(record.id);}));dialog.show();
    }

    private void showFieldRecord(long id){
        FieldDatabase.FieldRecord r=db.getFieldRecord(id);if(r==null){showFieldRecords();return;}
        LinearLayout root=page();root.addView(title(r.name));
        StringBuilder s=new StringBuilder();s.append(CoordinateFormats.decimal(r.lat,r.lon));if(r.accuracy>=0)s.append(String.format(Locale.US,"\nGPS accuracy: ±%.1f m",r.accuracy));if(Double.isFinite(r.altitude))s.append(String.format(Locale.US,"\nElevation: %.1f m",r.altitude));if(!r.category.isEmpty())s.append("\nCategory: ").append(r.category);if(!r.mineral.isEmpty())s.append("\nMineral/material: ").append(r.mineral);if(!r.sampleId.isEmpty())s.append("\nSample ID: ").append(r.sampleId);if(!r.notes.isEmpty())s.append("\n\n").append(r.notes);s.append("\n\nUpdated: ").append(DateFormat.getDateTimeInstance().format(new Date(r.updatedAt)));root.addView(help(s.toString()));
        if(r.photoUri!=null&&!r.photoUri.isEmpty())root.addView(action("Open attached photo",r.photoUri,v->openPhoto(r.photoUri)));
        root.addView(action("Navigate to this point","Live straight-line distance and bearing from GPS. This is not route guidance.",v->showPointNavigation(r.name,new GeoMath.Point(r.lat,r.lon))));
        LinearLayout row=row();row.addView(small("Edit",v->editFieldRecord(r)),weight());row.addView(small("Delete",v->new AlertDialog.Builder(this).setTitle("Delete field record?").setMessage(r.name+" will be removed from this device.").setPositiveButton("Delete",(d,w)->{db.deleteFieldRecord(r.id);showFieldRecords();}).setNegativeButton("Cancel",null).show()),weight());root.addView(row);root.addView(nav("Back to field records",v->showFieldRecords()));setContentView(scroll(root));
    }

    private void beginPhotoPick(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("image/*");startActivityForResult(i,REQ_PHOTO);}
    private void openPhoto(String uri){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}catch(RuntimeException ex){toast("Photo could not be opened.");}}

    // ---------- LEGACY SAVED LOCATIONS ----------

    private void showLegacyWaypoints(){
        waypointRepository.getAll(items->{LinearLayout root=page();root.addView(title("Saved locations"));root.addView(help("These are the existing RockMap markers used by the current map. Copying one to a Field Record leaves the original marker untouched."));
            if(items.isEmpty())root.addView(help("No saved locations yet."));
            else for(WaypointEntity w:items){root.addView(action(w.name,CoordinateFormats.decimal(w.latitude,w.longitude),v->showLegacyWaypoint(w)));}
            root.addView(nav("Back to Field",v->showHub()));setContentView(scroll(root));});
    }
    private void showLegacyWaypoint(WaypointEntity w){
        LinearLayout root=page();root.addView(title(w.name));root.addView(help(CoordinateFormats.decimal(w.latitude,w.longitude)+(w.accuracyMeters>=0?String.format(Locale.US,"\nGPS accuracy: ±%.1f m",w.accuracyMeters):"")+(w.notes==null||w.notes.trim().isEmpty()?"":"\n\n"+w.notes)));
        root.addView(action("Navigate to this point","Live straight-line distance and bearing from GPS. This is not route guidance.",v->showPointNavigation(w.name,new GeoMath.Point(w.latitude,w.longitude))));
        root.addView(action("Copy to Field Record","Creates a richer editable field record; the original map marker remains.",v->{long now=System.currentTimeMillis();FieldDatabase.FieldRecord r=new FieldDatabase.FieldRecord(0,w.name,"Saved location","","",w.notes,w.latitude,w.longitude,Double.NaN,w.accuracyMeters,"",now,now);r.id=db.insertFieldRecord(r);showFieldRecord(r.id);}));root.addView(nav("Back to saved locations",v->showLegacyWaypoints()));setContentView(scroll(root));
    }

    // ---------- MEASURE / AREAS ----------

    private void showMeasure(){
        LinearLayout root=page();root.addView(title("Measure & areas"));root.addView(help("Enter one coordinate per line. RockMap calculates total path distance, first-to-last bearing, and polygon area when 3+ points are present. This works offline."));
        EditText points=input("39.7392, -104.9903\n39.7400, -104.9800\n…","");points.setSingleLine(false);points.setMinLines(7);root.addView(points);
        TextView result=help("No measurement yet.");root.addView(result);
        LinearLayout row=row();row.addView(small("Add GPS",v->runWithPreciseLocation(()->locationRepository.requestFreshPrecise(l->{String old=points.getText().toString().trim();points.setText(old+(old.isEmpty()?"":"\n")+CoordinateFormats.decimal(l.getLatitude(),l.getLongitude()));},this::toast))),weight());row.addView(small("Calculate",v->{try{List<GeoMath.Point> parsed=parseLines(points.getText().toString());result.setText(measurementText(parsed));}catch(IllegalArgumentException ex){toast(ex.getMessage());}}),weight());root.addView(row);
        root.addView(action("Save as prospecting area","Requires at least 3 coordinate lines.",v->{try{List<GeoMath.Point> parsed=parseLines(points.getText().toString());if(parsed.size()<3)throw new IllegalArgumentException("Enter at least 3 coordinates to save an area.");promptSaveArea(parsed);}catch(IllegalArgumentException ex){toast(ex.getMessage());}}));
        root.addView(section("Saved areas"));List<FieldDatabase.Area> areas=db.listAreas();if(areas.isEmpty())root.addView(help("No saved areas yet."));else for(FieldDatabase.Area a:areas)root.addView(action(a.name,GeoMath.areaLabel(GeoMath.polygonAreaSquareMeters(a.points))+" · "+a.points.size()+" vertices",v->showArea(a)));root.addView(back());setContentView(scroll(root));
    }

    private List<GeoMath.Point> parseLines(String raw){ArrayList<GeoMath.Point> out=new ArrayList<>();for(String line:raw.split("\\r?\\n")){String t=line.trim();if(t.isEmpty())continue;try{CoordinateParser.Result r=CoordinateParser.parse(t);out.add(new GeoMath.Point(r.latitude,r.longitude));}catch(IllegalArgumentException ex){throw new IllegalArgumentException("Could not parse: "+t+" — "+ex.getMessage());}}if(out.size()<2)throw new IllegalArgumentException("Enter at least 2 coordinates.");if(out.size()>2000)throw new IllegalArgumentException("Measurement is limited to 2,000 points.");return out;}
    private String measurementText(List<GeoMath.Point> pts){double distance=GeoMath.pathDistanceMeters(pts);double bearing=GeoMath.initialBearingDegrees(pts.get(0),pts.get(pts.size()-1));String out="Path distance: "+GeoMath.distanceLabel(distance)+"\nFirst → last bearing: "+String.format(Locale.US,"%.0f° %s",bearing,GeoMath.cardinal(bearing));if(pts.size()>=3)out+="\nPolygon area: "+GeoMath.areaLabel(GeoMath.polygonAreaSquareMeters(pts));return out;}
    private void promptSaveArea(List<GeoMath.Point> pts){EditText name=input("Area name","");new AlertDialog.Builder(this).setTitle("Save prospecting area").setMessage(measurementText(pts)).setView(name).setPositiveButton("Save",(d,w)->{db.insertArea(name.getText().toString().trim(),"Saved from measurement tool",pts);showMeasure();}).setNegativeButton("Cancel",null).show();}
    private void showArea(FieldDatabase.Area a){LinearLayout root=page();root.addView(title(a.name));root.addView(help(a.points.size()+" vertices\n"+measurementText(a.points)+(a.notes==null||a.notes.isEmpty()?"":"\n\n"+a.notes)));BreadcrumbView view=new BreadcrumbView(this);view.setPoints(a.points);root.addView(view,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(250)));Button del=button("Delete area");del.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Delete area?").setPositiveButton("Delete",(d,w)->{db.deleteArea(a.id);showMeasure();}).setNegativeButton("Cancel",null).show());root.addView(del);root.addView(nav("Back to measure",v->showMeasure()));setContentView(scroll(root));}

    // ---------- IMPORT ----------

    private void beginImport(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,REQ_IMPORT);}
    private void handleImport(Uri uri){
        try{byte[] bytes=read(uri,MAX_IMPORT_BYTES);String name=displayName(uri);FieldImport.Result result=FieldImport.parse(bytes,name);String summary="Found:\n"+result.waypoints.size()+" waypoints\n"+result.tracks.size()+" tracks\n"+result.areas.size()+" polygon areas\n"+result.pointCount+" total geometry points\n\nImport is additive. Existing RockMap data will not be replaced.";new AlertDialog.Builder(this).setTitle("Import "+name+"?").setMessage(summary).setPositiveButton("Import",(d,w)->applyImport(result)).setNegativeButton("Cancel",null).show();}catch(Exception ex){toast("Import rejected: "+ex.getMessage());}
    }
    private void applyImport(FieldImport.Result r){
        final int[] remaining={r.tracks.size()+r.areas.size()};
        for(FieldImport.ImportedTrack t:r.tracks){long id=db.createTrack(t.name,System.currentTimeMillis());for(GeoMath.Point p:t.points)db.addTrackPoint(id,p);db.setTrackStatus(id,FieldDatabase.TRACK_COMPLETE,System.currentTimeMillis());}
        for(FieldImport.ImportedArea a:r.areas)db.insertArea(a.name,"Imported area",a.points);
        if(!r.waypoints.isEmpty())waypointRepository.insertAll(r.waypoints,count->{toast("Imported "+count+" waypoints, "+r.tracks.size()+" tracks and "+r.areas.size()+" areas.");showHub();});else{toast("Imported "+r.tracks.size()+" tracks and "+r.areas.size()+" areas.");showHub();}
    }

    // ---------- COORDINATES ----------

    private void showCoordinates(){
        LinearLayout root=page();root.addView(title("Coordinate formats"));root.addView(help("Enter latitude/longitude in decimal, DDM or DMS. Output uses WGS84. UTM/MGRS are displayed for supported latitudes."));EditText input=input("Latitude, longitude","");root.addView(input);TextView output=help("No coordinate converted yet.");output.setTextIsSelectable(true);root.addView(output);
        LinearLayout row=row();row.addView(small("Use GPS",v->runWithPreciseLocation(()->locationRepository.requestFreshPrecise(l->{input.setText(CoordinateFormats.decimal(l.getLatitude(),l.getLongitude()));renderFormats(input,output);},this::toast))),weight());row.addView(small("Convert",v->renderFormats(input,output)),weight());root.addView(row);root.addView(back());setContentView(scroll(root));
    }
    private void renderFormats(EditText input,TextView output){try{CoordinateParser.Result r=CoordinateParser.parse(input.getText().toString());CoordinateFormats.Utm utm=CoordinateFormats.toUtm(r.latitude,r.longitude);output.setText("Decimal\n"+CoordinateFormats.decimal(r.latitude,r.longitude)+"\n\nDDM\n"+CoordinateFormats.ddm(r.latitude,r.longitude)+"\n\nDMS\n"+CoordinateFormats.dms(r.latitude,r.longitude)+"\n\nUTM (WGS84)\n"+utm.label()+"\n\nMGRS (5-digit grid)\n"+CoordinateFormats.mgrs(r.latitude,r.longitude));}catch(IllegalArgumentException ex){input.setError(ex.getMessage());}}

    private void showPointNavigation(String name, GeoMath.Point target){
        navigationTarget=target; navigationTitle=name==null?"Target":name;
        LinearLayout root=page(); root.addView(title("Navigate to "+navigationTitle));
        navigationStatus=help("Getting a fresh GPS fix…"); root.addView(navigationStatus);
        root.addView(help("Target: "+target.decimal()+"\nStraight-line bearing only. RockMap does not calculate a safe/legal route, trail condition, cliff exposure or private-property access."));
        root.addView(nav("Back to Field",v->{navigationTarget=null;navigationStatus=null;showHub();}));
        setContentView(scroll(root));
        runWithPreciseLocation(()->locationRepository.requestFreshPrecise(this::updateNavigation,this::toast));
    }

    private void updateNavigation(Location location){
        if(navigationTarget==null||navigationStatus==null||location==null)return;
        GeoMath.Point current=point(location); double distance=GeoMath.distanceMeters(current,navigationTarget); double bearing=GeoMath.initialBearingDegrees(current,navigationTarget);
        navigationStatus.setText("Distance: "+GeoMath.distanceLabel(distance)+"\nBearing: "+String.format(Locale.US,"%.0f° %s",bearing,GeoMath.cardinal(bearing))+"\nCurrent: "+current.decimal());
    }

    // ---------- LOCATION / ACTIVITY RESULTS ----------

    private void runWithPreciseLocation(Runnable action){
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){action.run();return;}
        pendingLocationAction=action;
        new AlertDialog.Builder(this).setTitle("Precise location required").setMessage("This field action needs a precise GPS fix. RockMap does not request Android background-location permission. Track recording continues only after you explicitly start it and runs as a visible Android foreground service.")
                .setPositiveButton("Continue",(d,w)->requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},REQ_LOCATION)).setNegativeButton("Cancel",(d,w)->pendingLocationAction=null).show();
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){super.onRequestPermissionsResult(requestCode,permissions,results);if(requestCode!=REQ_LOCATION)return;Runnable pending=pendingLocationAction;pendingLocationAction=null;if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){if(started)locationRepository.start();if(pending!=null)pending.run();}else toast("Precise location was not granted.");}
    @Override public void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(requestCode==REQ_IMPORT)handleImport(uri);else if(requestCode==REQ_PHOTO){try{getContentResolver().takePersistableUriPermission(uri,data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(RuntimeException ignored){}pendingPhotoUri=uri.toString();toast("Photo attached. Tap Save to keep the field record.");}}
    @Override public void onLocation(Location location) { if(navigationTarget!=null) updateNavigation(location); }
    @Override public void onLocationError(String message){toast(message);}
    @Override protected void onStart(){super.onStart();started=true;if(locationRepository.hasCoarsePermission())locationRepository.start();}
    @Override protected void onStop(){started=false;locationRepository.stop();super.onStop();}
    @Override protected void onDestroy(){waypointRepository.close();super.onDestroy();}

    // ---------- UI / IO ----------

    private LinearLayout page(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(18),dp(14),dp(18),dp(24));l.setBackgroundColor(0xfffafafa);return l;}
    private ScrollView scroll(View content){ScrollView s=new ScrollView(this);s.setFillViewport(true);s.addView(content);s.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});s.requestApplyInsets();return s;}
    private TextView title(String text){TextView t=new TextView(this);t.setText(text);t.setTextSize(24f);t.setTextColor(0xff202020);t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);t.setPadding(0,0,0,dp(8));return t;}
    private TextView section(String text){TextView t=new TextView(this);t.setText(text);t.setTextSize(16f);t.setTextColor(0xff303030);t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);t.setPadding(0,dp(18),0,dp(6));return t;}
    private TextView help(String text){TextView t=new TextView(this);t.setText(text);t.setTextSize(13f);t.setTextColor(0xff555555);t.setPadding(0,0,0,dp(10));return t;}
    private Button button(String text){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(14f);b.setMinHeight(dp(50));b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);return b;}
    private View action(String title,String detail,View.OnClickListener listener){LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(14),dp(10),dp(14),dp(10));card.setBackgroundColor(0xffffffff);TextView h=new TextView(this);h.setText(title+"  ›");h.setTextSize(16f);h.setTextColor(0xff205b93);h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);card.addView(h);TextView d=help(detail);d.setPadding(0,dp(3),0,0);card.addView(d);card.setClickable(true);card.setFocusable(true);card.setMinimumHeight(dp(68));card.setOnClickListener(listener);LinearLayout wrap=new LinearLayout(this);wrap.setPadding(0,dp(4),0,dp(4));wrap.addView(card,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));return wrap;}
    private Button nav(String text,View.OnClickListener listener){Button b=button(text);b.setOnClickListener(listener);return b;}
    private Button back(){return nav("Back to map",v->finish());}
    private Button small(String text,View.OnClickListener l){Button b=button(text);b.setGravity(Gravity.CENTER);b.setOnClickListener(l);return b;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);return r;}
    private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f);}
    private EditText input(String hint,String value){EditText e=new EditText(this);e.setHint(hint);e.setText(value==null?"":value);e.setSingleLine(true);e.setTextSize(14f);e.setPadding(dp(8),dp(8),dp(8),dp(8));return e;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void toast(String text){Toast.makeText(this,text==null?"":text,Toast.LENGTH_LONG).show();}
    private String bounded(String value,int max){if(value==null)return"";return value.length()<=max?value:value.substring(0,max);}
    private GeoMath.Point point(Location l){return new GeoMath.Point(l.getLatitude(),l.getLongitude(),l.hasAltitude()?l.getAltitude():Double.NaN,l.hasAccuracy()?l.getAccuracy():-1f,l.getTime()>0?l.getTime():System.currentTimeMillis());}
    private String durationLabel(long ms){long minutes=ms/60000L;long hours=minutes/60L;minutes%=60L;return hours>0?hours+"h "+minutes+"m":minutes+"m";}
    private byte[] read(Uri uri,int max)throws IOException{try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in==null)throw new IOException("Android could not open the selected file.");byte[] buf=new byte[16384];int total=0,n;while((n=in.read(buf))!=-1){total+=n;if(total>max)throw new IOException("Selected file exceeds the 10 MB import limit.");out.write(buf,0,n);}return out.toByteArray();}}
    private String displayName(Uri uri){String fallback=uri.getLastPathSegment()==null?"selected file":uri.getLastPathSegment();try(android.database.Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()){String n=c.getString(0);if(n!=null&&!n.trim().isEmpty())return n.trim();}}catch(RuntimeException ignored){}return fallback;}
}
