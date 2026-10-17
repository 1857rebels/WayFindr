package btools.routingapp;

// Interface of the BRouter app's routing service (https://github.com/abrensch/brouter,
// brouter-routing-app). Must match the app's own copy: same package, name and method.
interface IBRouterService {
    // Computes a route from the parameters in the bundle and returns the track as text
    // (GeoJSON when trackFormat = "json"), or an error message.
    String getTrackFromParams(in Bundle params);
}
