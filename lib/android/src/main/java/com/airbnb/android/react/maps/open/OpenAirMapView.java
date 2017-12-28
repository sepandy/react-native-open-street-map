package com.airbnb.android.react.maps.open;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.os.Build;
import android.support.v4.view.GestureDetectorCompat;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;

import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.UIManagerModule;
import com.facebook.react.uimanager.events.EventDispatcher;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MapStyleOptions;

import org.osmdroid.tileprovider.modules.MapTileFileStorageProviderBase;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static android.support.v4.content.PermissionChecker.checkSelfPermission;

public class OpenAirMapView extends MapView {
    public MapView map;
    private ProgressBar mapLoadingProgressBar;
    private RelativeLayout mapLoadingLayout;
    private ImageView cacheImageView;
    private Boolean isMapLoaded = false;
    private Integer loadingBackgroundColor = null;
    private Integer loadingIndicatorColor = null;
    private final int baseMapPadding = 50;

    private boolean showUserLocation = false;
    private boolean handlePanDrag = false;
    private boolean moveOnMarkerPress = true;
    private boolean cacheEnabled = false;
    private boolean initialRegionSet = false;
    private LatLngBounds cameraLastIdleBounds;
    private int cameraMoveReason = 0;

    private static final String[] PERMISSIONS = new String[] {
            "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"
    };

    private final List<OpenAirMapFeature> features = new ArrayList<>();
    private final Map<Polyline, OpenAirMapPolyline> polylineMap = new HashMap<>();
    private final GestureDetectorCompat gestureDetector;
    private final OpenAirMapManager manager;
    private LifecycleEventListener lifecycleListener;
    private boolean paused = false;
    private boolean destroyed = false;
    private final ThemedReactContext context;
    private final EventDispatcher eventDispatcher;

    private static boolean contextHasBug(Context context) {
        return context == null ||
        context.getResources() == null ||
        context.getResources().getConfiguration() == null;
    }

    // We do this to fix this bug:
    // https://github.com/airbnb/react-native-maps/issues/271
    //
    // which conflicts with another bug regarding the passed in context:
    // https://github.com/airbnb/react-native-maps/issues/1147
    //
    // Doing this allows us to avoid both bugs.
    private static Context getNonBuggyContext(ThemedReactContext reactContext, ReactApplicationContext appContext) {
        Context superContext = reactContext;
        if (!contextHasBug(appContext.getCurrentActivity())) {
            superContext = appContext.getCurrentActivity();
        } else if (contextHasBug(superContext)) {
        // we have the bug! let's try to find a better context to use
            if (!contextHasBug(reactContext.getCurrentActivity())) {
            superContext = reactContext.getCurrentActivity();
            } else if (!contextHasBug(reactContext.getApplicationContext())) {
            superContext = reactContext.getApplicationContext();
            } else {
            // ¯\_(ツ)_/¯
            }
        }
        return superContext;
    }

    public OpenAirMapView(ThemedReactContext reactContext,
                      ReactApplicationContext appContext,
                      OpenAirMapManager manager)
    {
        super(getNonBuggyContext(reactContext, appContext));

        this.manager = manager;
        this.context = reactContext;

        super.onCreate(null);
        // TODO(lmr): what about onStart????
        super.onResume();
        super.getMapAsync(this);

final OpenAirMapView view = this;

        gestureDetector =
        new GestureDetectorCompat(reactContext, new GestureDetector.SimpleOnGestureListener() {

        @Override
        public boolean onScroll(MotionEvent e1,
                                MotionEvent e2,
                                float distanceX,
                                float distanceY) {
            if (handlePanDrag) {
                onPanDrag(e2);
            }
            return false;
        }
    });

        this.addOnLayoutChangeListener(new OnLayoutChangeListener() {
@Override public void onLayoutChange(View v, int left, int top, int right, int bottom,
        int oldLeft, int oldTop, int oldRight, int oldBottom) {
        if (!paused) {
        OpenAirMapView.this.cacheView();
        }
        }
        });

        eventDispatcher = reactContext.getNativeModule(UIManagerModule.class).getEventDispatcher();
    }

    @Override
    public void onMapReady(final MapView map) {
        if (destroyed) {
            return;
        }
        this.map = map;
        this.map.setInfoWindowAdapter(this);
        this.map.setOnMarkerDragListener(this);

        manager.pushEvent(context, this, "onMapReady", new WritableNativeMap());
        final OpenAirMapView view = this;
        context.addLifecycleEventListener(lifecycleListener);
    }

    private boolean hasPermissions() {
        int permission0 = checkSelfPermission(getContext(), PERMISSIONS[0]);
        int permission1 = checkSelfPermission(getContext(), PERMISSIONS[1]);

        return permission0 == PackageManager.PERMISSION_GRANTED ||
               permission1 == PackageManager.PERMISSION_GRANTED;
    }

    /*
      onDestroy is final method so I can't override it.
   */
    public synchronized void doDestroy() {
        if (destroyed) {
            return;
        }

        destroyed = true;

        if (lifecycleListener != null && context != null) {
            context.removeLifecycleEventListener(lifecycleListener);
            lifecycleListener = null;
        }
        if (!paused) {
            onPause();
            paused = true;
        }
        onDestroy();
    }

    public void setInitialRegion(ReadableMap initialRegion) {
        if (!initialRegionSet && initialRegion != null) {
            setRegion(initialRegion);
            initialRegionSet = true;
        }
    }

    public void setRegion(ReadableMap region) {
        if (region == null) return;

        Double lng = region.getDouble("longitude");
        Double lat = region.getDouble("latitude");
        Double lngDelta = region.getDouble("longitudeDelta");
        Double latDelta = region.getDouble("latitudeDelta");
        LatLng southwest = new LatLng(lat - latDelta / 2, lng - lngDelta / 2);
        LatLng northeast = new LatLng(lat + latDelta / 2, lng + lngDelta / 2);

        LatLngBounds bounds = new LatLngBounds(southwest, northeast);

        if (super.getHeight() <= 0 || super.getWidth() <= 0) {
            // in this case, our map has not been laid out yet, so we save the bounds in a local
            // variable, and make a guess of zoomLevel 10. Not to worry, though: as soon as layout
            // occurs, we will move the camera to the saved bounds. Note that if we tried to move
            // to the bounds now, it would trigger an exception.
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lng), 10));
            boundsToMove = bounds;
        } else {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0));
            boundsToMove = null;
        }
    }

    public void setShowsUserLocation(boolean showUserLocation) {
        // hold onto this for lifecycle handling
        this.showUserLocation = showUserLocation;
        if (hasPermissions()) {
            //noinspection MissingPermission
            map.setMyLocationEnabled(showUserLocation);
        }
    }

    public void setShowsMyLocationButton(boolean showMyLocationButton) {
        if (hasPermissions()) {
            map.getUiSettings().setMyLocationButtonEnabled(showMyLocationButton);
        }
    }

    public void setToolbarEnabled(boolean toolbarEnabled) {
        if (hasPermissions()) {
            map.getUiSettings().setMapToolbarEnabled(toolbarEnabled);
        }
    }

    public void setCacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
        this.cacheView();
    }

    public void enableMapLoading(boolean loadingEnabled) {
        if (loadingEnabled && !this.isMapLoaded) {
        this.getMapLoadingLayoutView().setVisibility(View.VISIBLE);
        }
    }

    public void setMoveOnMarkerPress(boolean moveOnPress) {
        this.moveOnMarkerPress = moveOnPress;
        }

    public void setLoadingBackgroundColor(Integer loadingBackgroundColor) {
        this.loadingBackgroundColor = loadingBackgroundColor;

        if (this.mapLoadingLayout != null) {
        if (loadingBackgroundColor == null) {
        this.mapLoadingLayout.setBackgroundColor(Color.WHITE);
        } else {
        this.mapLoadingLayout.setBackgroundColor(this.loadingBackgroundColor);
        }
        }
    }

    public void setLoadingIndicatorColor(Integer loadingIndicatorColor) {
        this.loadingIndicatorColor = loadingIndicatorColor;
        if (this.mapLoadingProgressBar != null) {
            Integer color = loadingIndicatorColor;
            if (color == null) {
                color = Color.parseColor("#606060");
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ColorStateList progressTintList = ColorStateList.valueOf(loadingIndicatorColor);
            ColorStateList secondaryProgressTintList = ColorStateList.valueOf(loadingIndicatorColor);
            ColorStateList indeterminateTintList = ColorStateList.valueOf(loadingIndicatorColor);

            this.mapLoadingProgressBar.setProgressTintList(progressTintList);
            this.mapLoadingProgressBar.setSecondaryProgressTintList(secondaryProgressTintList);
            this.mapLoadingProgressBar.setIndeterminateTintList(indeterminateTintList);
        } else {
            PorterDuff.Mode mode = PorterDuff.Mode.SRC_IN;
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.GINGERBREAD_MR1) {
                mode = PorterDuff.Mode.MULTIPLY;
            }
            if (this.mapLoadingProgressBar.getIndeterminateDrawable() != null)
                this.mapLoadingProgressBar.getIndeterminateDrawable().setColorFilter(color, mode);
            if (this.mapLoadingProgressBar.getProgressDrawable() != null)
                this.mapLoadingProgressBar.getProgressDrawable().setColorFilter(color, mode);
            }
        }
    }

    public void setHandlePanDrag(boolean handlePanDrag) {
            this.handlePanDrag = handlePanDrag;
            }

    public void addFeature(View child, int index) {
        // Our desired API is to pass up annotations/overlays as children to the mapview component.
        // This is where we intercept them and do the appropriate underlying mapview action.
        if (child instanceof AirMapMarker) {
        AirMapMarker annotation = (AirMapMarker) child;
        annotation.addToMap(map);
        features.add(index, annotation);
        Marker marker = (Marker) annotation.getFeature();
        markerMap.put(marker, annotation);
        } else if (child instanceof AirMapPolyline) {
        AirMapPolyline polylineView = (AirMapPolyline) child;
        polylineView.addToMap(map);
        features.add(index, polylineView);
        Polyline polyline = (Polyline) polylineView.getFeature();
        polylineMap.put(polyline, polylineView);
        } else if (child instanceof AirMapPolygon) {
        AirMapPolygon polygonView = (AirMapPolygon) child;
        polygonView.addToMap(map);
        features.add(index, polygonView);
        Polygon polygon = (Polygon) polygonView.getFeature();
        polygonMap.put(polygon, polygonView);
        } else if (child instanceof AirMapCircle) {
        AirMapCircle circleView = (AirMapCircle) child;
        circleView.addToMap(map);
        features.add(index, circleView);
        } else if (child instanceof AirMapUrlTile) {
        AirMapUrlTile urlTileView = (AirMapUrlTile) child;
        urlTileView.addToMap(map);
        features.add(index, urlTileView);
        } else if (child instanceof AirMapLocalTile) {
        AirMapLocalTile localTileView = (AirMapLocalTile) child;
        localTileView.addToMap(map);
        features.add(index, localTileView);
        } else if (child instanceof ViewGroup) {
        ViewGroup children = (ViewGroup) child;
        for (int i = 0; i < children.getChildCount(); i++) {
        addFeature(children.getChildAt(i), index);
        }
        } else {
        addView(child, index);
        }
    }

    public int getFeatureCount() {
        return features.size();
        }

    public View getFeatureAt(int index) {
        return features.get(index);
        }

    public void removeFeatureAt(int index) {
        AirMapFeature feature = features.remove(index);
        if (feature instanceof AirMapMarker) {
        markerMap.remove(feature.getFeature());
        }
        feature.removeFromMap(map);
    }

    public WritableMap makeClickEventData(LatLng point) {
        WritableMap event = new WritableNativeMap();

        WritableMap coordinate = new WritableNativeMap();
        coordinate.putDouble("latitude", point.latitude);
        coordinate.putDouble("longitude", point.longitude);
        event.putMap("coordinate", coordinate);

        Projection projection = map.getProjection();
        Point screenPoint = projection.toScreenLocation(point);

        WritableMap position = new WritableNativeMap();
        position.putDouble("x", screenPoint.x);
        position.putDouble("y", screenPoint.y);
        event.putMap("position", position);

        return event;
    }

    public void updateExtraData(Object extraData) {
        // if boundsToMove is not null, we now have the MapView's width/height, so we can apply
        // a proper camera move
        if (boundsToMove != null) {
            HashMap<String, Float> data = (HashMap<String, Float>) extraData;
            int width = data.get("width") == null ? 0 : data.get("width").intValue();
            int height = data.get("height") == null ? 0 : data.get("height").intValue();

            //fix for https://github.com/airbnb/react-native-maps/issues/245,
            //it's not guaranteed the passed-in height and width would be greater than 0.
            if (width <= 0 || height <= 0) {
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(boundsToMove, 0));
            } else {
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(boundsToMove, width, height, 0));
            }

            boundsToMove = null;
        }
    }

    public void animateToRegion(GeoPoint bounds, int duration) {
        if (map == null) return;
        map.cam
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0), duration, null);
    }

    public void animateToViewingAngle(float angle, int duration) {
        if (map == null) return;

        CameraPosition cameraPosition = new CameraPosition.Builder(map.getCameraPosition())
        .tilt(angle)
        .build();
        map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), duration, null);
    }

    public void animateToBearing(float bearing, int duration) {
        if (map == null) return;
            CameraPosition cameraPosition = new CameraPosition.Builder(map.getCameraPosition())
            .bearing(bearing)
            .build();
            map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), duration, null);
        }

    public void animateToCoordinate(LatLng coordinate, int duration) {
        if (map == null) return;
        map.animateCamera(CameraUpdateFactory.newLatLng(coordinate), duration, null);
    }

    public void fitToElements(boolean animated) {
        if (map == null) return;

        LatLngBounds.Builder builder = new LatLngBounds.Builder();

        boolean addedPosition = false;

        for (OpenAirMapFeature feature : features) {
            if (feature instanceof OpenAirMapMarker) {
                Marker marker = (Marker) feature.getFeature();
                builder.include(marker.getPosition());
                addedPosition = true;
            }
        // TODO(lmr): may want to include shapes / etc.
        }
        if (addedPosition) {
            LatLngBounds bounds = builder.build();
            CameraUpdate cu = CameraUpdateFactory.newLatLngBounds(bounds, baseMapPadding);
        if (animated) {
            map.animateCamera(cu);
        } else {
            map.moveCamera(cu);
        }
        }
    }

    public void fitToSuppliedMarkers(ReadableArray markerIDsArray, boolean animated) {
        if (map == null) return;

        LatLngBounds.Builder builder = new LatLngBounds.Builder();

        String[] markerIDs = new String[markerIDsArray.size()];
        for (int i = 0; i < markerIDsArray.size(); i++) {
            markerIDs[i] = markerIDsArray.getString(i);
        }

        boolean addedPosition = false;

        List<String> markerIDList = Arrays.asList(markerIDs);

        for (AirMapFeature feature : features) {
            if (feature instanceof AirMapMarker) {
                String identifier = ((AirMapMarker) feature).getIdentifier();
                Marker marker = (Marker) feature.getFeature();
                if (markerIDList.contains(identifier)) {
                    builder.include(marker.getPosition());
                    addedPosition = true;
                }
            }
        }

        if (addedPosition) {
            LatLngBounds bounds = builder.build();
            CameraUpdate cu = CameraUpdateFactory.newLatLngBounds(bounds, baseMapPadding);
            if (animated) {
                map.animateCamera(cu);
            } else {
             map.moveCamera(cu);
            }
        }
    }

    public void fitToCoordinates(ReadableArray coordinatesArray, ReadableMap edgePadding,
                                 boolean animated) {
        if (map == null) return;

        LatLngBounds.Builder builder = new LatLngBounds.Builder();

        for (int i = 0; i < coordinatesArray.size(); i++) {
            ReadableMap latLng = coordinatesArray.getMap(i);
            Double lat = latLng.getDouble("latitude");
            Double lng = latLng.getDouble("longitude");
            builder.include(new LatLng(lat, lng));
        }

        LatLngBounds bounds = builder.build();
        CameraUpdate cu = CameraUpdateFactory.newLatLngBounds(bounds, baseMapPadding);

        if (edgePadding != null) {
            map.setPadding(edgePadding.getInt("left"), edgePadding.getInt("top"),
            edgePadding.getInt("right"), edgePadding.getInt("bottom"));
        }

        if (animated) {
            map.animateCamera(cu);
        } else {
            map.moveCamera(cu);
        }
        map.setPadding(0, 0, 0,
        0); // Without this, the Google logo is moved up by the value of edgePadding.bottom
    }

    public void setMapBoundaries(ReadableMap northEast, ReadableMap southWest) {
        if (map == null) return;

        LatLngBounds.Builder builder = new LatLngBounds.Builder();

        Double latNE = northEast.getDouble("latitude");
        Double lngNE = northEast.getDouble("longitude");
        builder.include(new LatLng(latNE, lngNE));

        Double latSW = southWest.getDouble("latitude");
        Double lngSW = southWest.getDouble("longitude");
        builder.include(new LatLng(latSW, lngSW));

        LatLngBounds bounds = builder.build();

        map.setLatLngBoundsForCameraTarget(bounds);
    }

// InfoWindowAdapter interface

    @Override
    public View getInfoWindow(Marker marker) {
        AirMapMarker markerView = markerMap.get(marker);
        return markerView.getCallout();
    }

    @Override
    public View getInfoContents(Marker marker) {
        AirMapMarker markerView = markerMap.get(marker);
        return markerView.getInfoContents();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        gestureDetector.onTouchEvent(ev);

        int action = MotionEventCompat.getActionMasked(ev);

        switch (action) {
        case (MotionEvent.ACTION_DOWN):
        this.getParent().requestDisallowInterceptTouchEvent(
        map != null && map.getUiSettings().isScrollGesturesEnabled());
        break;
        case (MotionEvent.ACTION_UP):
        // Clear this regardless, since isScrollGesturesEnabled() may have been updated
        this.getParent().requestDisallowInterceptTouchEvent(false);
        break;
        }
        super.dispatchTouchEvent(ev);
        return true;
    }

    @Override
    public void onMarkerDragStart(Marker marker) {
        WritableMap event = makeClickEventData(marker.getPosition());
        manager.pushEvent(context, this, "onMarkerDragStart", event);

        AirMapMarker markerView = markerMap.get(marker);
        event = makeClickEventData(marker.getPosition());
        manager.pushEvent(context, markerView, "onDragStart", event);
    }

    @Override
    public void onMarkerDrag(Marker marker) {
        WritableMap event = makeClickEventData(marker.getPosition());
        manager.pushEvent(context, this, "onMarkerDrag", event);

        AirMapMarker markerView = markerMap.get(marker);
        event = makeClickEventData(marker.getPosition());
        manager.pushEvent(context, markerView, "onDrag", event);
    }

    @Override
    public void onMarkerDragEnd(Marker marker) {
        WritableMap event = makeClickEventData(marker.getPosition());
        manager.pushEvent(context, this, "onMarkerDragEnd", event);

        OpenAirMapMarker markerView = markerMap.get(marker);
        event = makeClickEventData(marker.getPosition());
        manager.pushEvent(context, markerView, "onDragEnd", event);
        }

    private ProgressBar getMapLoadingProgressBar() {
        if (this.mapLoadingProgressBar == null) {
        this.mapLoadingProgressBar = new ProgressBar(getContext());
        this.mapLoadingProgressBar.setIndeterminate(true);
        }
        if (this.loadingIndicatorColor != null) {
        this.setLoadingIndicatorColor(this.loadingIndicatorColor);
        }
        return this.mapLoadingProgressBar;
    }

    private RelativeLayout getMapLoadingLayoutView() {
        if (this.mapLoadingLayout == null) {
        this.mapLoadingLayout = new RelativeLayout(getContext());
        this.mapLoadingLayout.setBackgroundColor(Color.LTGRAY);
        this.addView(this.mapLoadingLayout,
        new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT));

        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
        RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
        params.addRule(RelativeLayout.CENTER_IN_PARENT);
        this.mapLoadingLayout.addView(this.getMapLoadingProgressBar(), params);

        this.mapLoadingLayout.setVisibility(View.INVISIBLE);
        }
        this.setLoadingBackgroundColor(this.loadingBackgroundColor);
        return this.mapLoadingLayout;
    }

    private ImageView getCacheImageView() {
        if (this.cacheImageView == null) {
            this.cacheImageView = new ImageView(getContext());
            this.addView(this.cacheImageView,
            new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
            this.cacheImageView.setVisibility(View.INVISIBLE);
        }
        return this.cacheImageView;
    }

    private void removeCacheImageView() {
        if (this.cacheImageView != null) {
        ((ViewGroup) this.cacheImageView.getParent()).removeView(this.cacheImageView);
        this.cacheImageView = null;
        }
    }

    private void removeMapLoadingProgressBar() {
        if (this.mapLoadingProgressBar != null) {
        ((ViewGroup) this.mapLoadingProgressBar.getParent()).removeView(this.mapLoadingProgressBar);
        this.mapLoadingProgressBar = null;
        }
    }

    private void removeMapLoadingLayoutView() {
        this.removeMapLoadingProgressBar();
        if (this.mapLoadingLayout != null) {
        ((ViewGroup) this.mapLoadingLayout.getParent()).removeView(this.mapLoadingLayout);
        this.mapLoadingLayout = null;
        }
    }

    private void cacheView() {
            if (this.cacheEnabled) {
    final ImageView cacheImageView = this.getCacheImageView();
    final RelativeLayout mapLoadingLayout = this.getMapLoadingLayoutView();
        cacheImageView.setVisibility(View.INVISIBLE);
        mapLoadingLayout.setVisibility(View.VISIBLE);
        if (this.isMapLoaded) {
        this.map.snapshot(new GoogleMap.SnapshotReadyCallback() {
@Override public void onSnapshotReady(Bitmap bitmap) {
        cacheImageView.setImageBitmap(bitmap);
        cacheImageView.setVisibility(View.VISIBLE);
        mapLoadingLayout.setVisibility(View.INVISIBLE);
        }
        });
        }
        } else {
        this.removeCacheImageView();
        if (this.isMapLoaded) {
        this.removeMapLoadingLayoutView();
        }
        }
    }

    public void onPanDrag(MotionEvent ev) {
        Point point = new Point((int) ev.getX(), (int) ev.getY());
        LatLng coords = this.map.getProjection().fromScreenLocation(point);
        WritableMap event = makeClickEventData(coords);
        manager.pushEvent(context, this, "onPanDrag", event);
    }
}