
/*global input, objectID */

(function () {
    // Create a change patch
    var patch = [{ "replace" : "active", "value" : false }];
    
    logger.debug("Performing Sunset Task on {} ({})", input.email, objectID);

    // Perform update via patch so that we can do so regardless of revision change
    // NOTE: If we were to use update and the object had been modified during script execution
    // update would fail due to conflicting revision numbers
    openidm.patch(objectID, null, patch);
    
    // Update method:
    // input['active'] = false;
    // openidm.update(objectID, input['_rev'], input);
    
    return true; // return true to indicate successful completion
}());
