package org.jellyfin.androidtv.querying;

import org.jellyfin.androidtv.TvApp;

import mediabrowser.model.querying.ItemFields;
import mediabrowser.model.querying.ItemQuery;

/**
 * Created by Eric on 12/5/2014.
 */
public class StdItemQuery extends ItemQuery {

    public StdItemQuery(ItemFields[] fields) {
        if (fields == null) fields = new ItemFields[] {ItemFields.PrimaryImageAspectRatio, ItemFields.Overview, ItemFields.ItemCounts, ItemFields.DisplayPreferencesId};
        setUserId(TvApp.getApplication().getCurrentUser().getId());
        setFields(fields);
    }

    public StdItemQuery() {
        this(null);
    }
}
