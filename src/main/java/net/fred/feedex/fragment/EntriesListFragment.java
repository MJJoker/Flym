/**
 * Flym
 * <p>
 * Copyright (c) 2012-2015 Frederic Julian
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.fred.feedex.fragment;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.BaseColumns;
import android.support.design.widget.Snackbar;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v7.widget.SearchView;
import android.support.v7.widget.SearchView.OnCloseListener;
import android.support.v7.widget.SearchView.OnQueryTextListener;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;

import net.fred.feedex.Constants;
import net.fred.feedex.MainApplication;
import net.fred.feedex.R;
import net.fred.feedex.R.color;
import net.fred.feedex.R.id;
import net.fred.feedex.R.layout;
import net.fred.feedex.R.string;
import net.fred.feedex.adapter.EntriesCursorAdapter;
import net.fred.feedex.provider.FeedData;
import net.fred.feedex.provider.FeedData.EntryColumns;
import net.fred.feedex.provider.FeedDataContentProvider;
import net.fred.feedex.service.FetcherService;
import net.fred.feedex.utils.PrefUtils;
import net.fred.feedex.utils.UiUtils;

import java.util.ArrayList;
import java.util.Date;

public class EntriesListFragment extends SwipeRefreshListFragment {

    private static final String STATE_CURRENT_URI = "STATE_CURRENT_URI";
    private static final String STATE_ORIGINAL_URI = "STATE_ORIGINAL_URI";
    private static final String STATE_SHOW_FEED_INFO = "STATE_SHOW_FEED_INFO";
    private static final String STATE_LIST_DISPLAY_DATE = "STATE_LIST_DISPLAY_DATE";

    private static final int ENTRIES_LOADER_ID = 1;
    private static final int NEW_ENTRIES_NUMBER_LOADER_ID = 2;
    private final OnSharedPreferenceChangeListener mPrefListener = new OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (PrefUtils.IS_REFRESHING.equals(key)) {
                refreshSwipeProgress();
            }
        }
    };
    private Uri mCurrentUri, mOriginalUri;
    private boolean mShowFeedInfo;
    private EntriesCursorAdapter mEntriesCursorAdapter;
    private Cursor mJustMarkedAsReadEntries;
    private ListView mListView;
    private long mListDisplayDate = new Date().getTime();
    private final LoaderManager.LoaderCallbacks<Cursor> mEntriesLoader = new LoaderManager.LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            String entriesOrder = PrefUtils.getBoolean(PrefUtils.DISPLAY_OLDEST_FIRST, false) ? Constants.DB_ASC : Constants.DB_DESC;
            String where = "(" + EntryColumns.FETCH_DATE + Constants.DB_IS_NULL + Constants.DB_OR + EntryColumns.FETCH_DATE + "<=" + EntriesListFragment.this.mListDisplayDate + ')';
            CursorLoader cursorLoader = new CursorLoader(EntriesListFragment.this.getActivity(), EntriesListFragment.this.mCurrentUri, null, where, null, EntryColumns.DATE + entriesOrder);
            cursorLoader.setUpdateThrottle(150);
            return cursorLoader;
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
            EntriesListFragment.this.mEntriesCursorAdapter.swapCursor(data);
        }

        @Override
        public void onLoaderReset(Loader<Cursor> loader) {
            EntriesListFragment.this.mEntriesCursorAdapter.swapCursor(Constants.EMPTY_CURSOR);
        }
    };
    private int mNewEntriesNumber, mOldUnreadEntriesNumber = -1;
    private boolean mAutoRefreshDisplayDate;
    private Button mRefreshListBtn;
    private final LoaderManager.LoaderCallbacks<Cursor> mEntriesNumberLoader = new LoaderManager.LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            CursorLoader cursorLoader = new CursorLoader(EntriesListFragment.this.getActivity(), EntriesListFragment.this.mCurrentUri, new String[]{"SUM(" + EntryColumns.FETCH_DATE + '>' + EntriesListFragment.this.mListDisplayDate + ")", "SUM(" + EntryColumns.FETCH_DATE + "<=" + EntriesListFragment.this.mListDisplayDate + Constants.DB_AND + EntryColumns.WHERE_UNREAD + ")"}, null, null, null);
            cursorLoader.setUpdateThrottle(150);
            return cursorLoader;
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
            data.moveToFirst();
            EntriesListFragment.this.mNewEntriesNumber = data.getInt(0);
            EntriesListFragment.this.mOldUnreadEntriesNumber = data.getInt(1);

            if (EntriesListFragment.this.mAutoRefreshDisplayDate && EntriesListFragment.this.mNewEntriesNumber != 0 && EntriesListFragment.this.mOldUnreadEntriesNumber == 0) {
                EntriesListFragment.this.mListDisplayDate = new Date().getTime();
                EntriesListFragment.this.restartLoaders();
            } else {
                EntriesListFragment.this.refreshUI();
            }

            EntriesListFragment.this.mAutoRefreshDisplayDate = false;
        }

        @Override
        public void onLoaderReset(Loader<Cursor> loader) {
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        this.setHasOptionsMenu(true);
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            this.mCurrentUri = savedInstanceState.getParcelable(EntriesListFragment.STATE_CURRENT_URI);
            this.mOriginalUri = savedInstanceState.getParcelable(EntriesListFragment.STATE_ORIGINAL_URI);
            this.mShowFeedInfo = savedInstanceState.getBoolean(EntriesListFragment.STATE_SHOW_FEED_INFO);
            this.mListDisplayDate = savedInstanceState.getLong(EntriesListFragment.STATE_LIST_DISPLAY_DATE);

            this.mEntriesCursorAdapter = new EntriesCursorAdapter(this.getActivity(), this.mCurrentUri, Constants.EMPTY_CURSOR, this.mShowFeedInfo);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        this.refreshUI(); // Should not be useful, but it's a security
        this.refreshSwipeProgress();
        PrefUtils.registerOnPrefChangeListener(this.mPrefListener);

        if (this.mCurrentUri != null) {
            // If the list is empty when we are going back here, try with the last display date
            if (this.mNewEntriesNumber != 0 && this.mOldUnreadEntriesNumber == 0) {
                this.mListDisplayDate = new Date().getTime();
            } else {
                this.mAutoRefreshDisplayDate = true; // We will try to update the list after if necessary
            }

            this.restartLoaders();
        }
    }

    @Override
    public View inflateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(layout.fragment_entry_list, container, true);

        if (this.mEntriesCursorAdapter != null) {
            this.setListAdapter(this.mEntriesCursorAdapter);
        }

        this.mListView = rootView.findViewById(android.R.id.list);
        UiUtils.addEmptyFooterView(this.mListView, 90);

        this.mRefreshListBtn = rootView.findViewById(id.refreshListBtn);
        this.mRefreshListBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                EntriesListFragment.this.mNewEntriesNumber = 0;
                EntriesListFragment.this.mListDisplayDate = new Date().getTime();

                EntriesListFragment.this.refreshUI();
                if (EntriesListFragment.this.mCurrentUri != null) {
                    EntriesListFragment.this.restartLoaders();
                }
            }
        });

        this.disableSwipe();

        return rootView;
    }

    @Override
    public void onStop() {
        PrefUtils.unregisterOnPrefChangeListener(this.mPrefListener);

        if (this.mJustMarkedAsReadEntries != null && !this.mJustMarkedAsReadEntries.isClosed()) {
            this.mJustMarkedAsReadEntries.close();
        }

        super.onStop();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putParcelable(EntriesListFragment.STATE_CURRENT_URI, this.mCurrentUri);
        outState.putParcelable(EntriesListFragment.STATE_ORIGINAL_URI, this.mOriginalUri);
        outState.putBoolean(EntriesListFragment.STATE_SHOW_FEED_INFO, this.mShowFeedInfo);
        outState.putLong(EntriesListFragment.STATE_LIST_DISPLAY_DATE, this.mListDisplayDate);

        super.onSaveInstanceState(outState);
    }

    @Override
    public void onRefresh() {
        this.startRefresh();
    }

    @Override
    public void onListItemClick(ListView listView, View view, int position, long id) {
        if (id >= 0) { // should not happen, but I had a crash with this on PlayStore...
            this.startActivity(new Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(this.mCurrentUri, id)));
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear(); // This is needed to remove a bug on Android 4.0.3

        inflater.inflate(menu.entry_list, menu);

        SearchView searchView = (SearchView) menu.findItem(id.menu_search).getActionView();
        searchView.setOnQueryTextListener(new OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                if (TextUtils.isEmpty(newText)) {
                    EntriesListFragment.this.setData(EntriesListFragment.this.mOriginalUri, true);
                } else {
                    EntriesListFragment.this.setData(EntryColumns.SEARCH_URI(newText), true, true);
                }
                return false;
            }
        });
        searchView.setOnCloseListener(new OnCloseListener() {
            @Override
            public boolean onClose() {
                EntriesListFragment.this.setData(EntriesListFragment.this.mOriginalUri, true);
                return false;
            }
        });

        if (EntryColumns.FAVORITES_CONTENT_URI.equals(this.mCurrentUri)) {
            menu.findItem(id.menu_refresh).setVisible(false);
        } else {
            menu.findItem(id.menu_share_starred).setVisible(false);
        }

        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case id.menu_share_starred:
                if (this.mEntriesCursorAdapter != null) {
                    String starredList = "";
                    Cursor cursor = this.mEntriesCursorAdapter.getCursor();
                    if (cursor != null && !cursor.isClosed()) {
                        int titlePos = cursor.getColumnIndex(EntryColumns.TITLE);
                        int linkPos = cursor.getColumnIndex(EntryColumns.LINK);
                        if (cursor.moveToFirst()) {
                            do {
                                starredList += cursor.getString(titlePos) + "\n" + cursor.getString(linkPos) + "\n\n";
                            } while (cursor.moveToNext());
                        }
                        this.startActivity(Intent.createChooser(
                                new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_SUBJECT, this.getString(string.share_favorites_title))
                                        .putExtra(Intent.EXTRA_TEXT, starredList).setType(Constants.MIMETYPE_TEXT_PLAIN), this.getString(string.menu_share)
                        ));
                    }
                }
                return true;
            case id.menu_refresh:
                this.startRefresh();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public void markAllAsRead() {
        if (this.mEntriesCursorAdapter != null) {
            Snackbar snackbar = Snackbar.make(this.getActivity().findViewById(id.coordinator_layout), string.marked_as_read, Snackbar.LENGTH_LONG)
                    .setActionTextColor(ContextCompat.getColor(this.getActivity(), color.light_theme_color_primary))
                    .setAction(string.undo, new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            new Thread() {
                                @Override
                                public void run() {
                                    if (EntriesListFragment.this.mJustMarkedAsReadEntries != null && !EntriesListFragment.this.mJustMarkedAsReadEntries.isClosed()) {
                                        ArrayList<Integer> ids = new ArrayList<>();
                                        while (EntriesListFragment.this.mJustMarkedAsReadEntries.moveToNext()) {
                                            ids.add(EntriesListFragment.this.mJustMarkedAsReadEntries.getInt(0));
                                        }
                                        ContentResolver cr = MainApplication.getContext().getContentResolver();
                                        String where = BaseColumns._ID + " IN (" + TextUtils.join(",", ids) + ')';
                                        cr.update(EntryColumns.CONTENT_URI, FeedData.getUnreadContentValues(), where, null);

                                        EntriesListFragment.this.mJustMarkedAsReadEntries.close();
                                    }
                                }
                            }.start();
                        }
                    });
            snackbar.getView().setBackgroundResource(color.material_grey_900);
            snackbar.show();

            new Thread() {
                @Override
                public void run() {
                    ContentResolver cr = MainApplication.getContext().getContentResolver();
                    String where = EntryColumns.WHERE_UNREAD + Constants.DB_AND + '(' + EntryColumns.FETCH_DATE + Constants.DB_IS_NULL + Constants.DB_OR + EntryColumns.FETCH_DATE + "<=" + EntriesListFragment.this.mListDisplayDate + ')';
                    if (EntriesListFragment.this.mJustMarkedAsReadEntries != null && !EntriesListFragment.this.mJustMarkedAsReadEntries.isClosed()) {
                        EntriesListFragment.this.mJustMarkedAsReadEntries.close();
                    }
                    EntriesListFragment.this.mJustMarkedAsReadEntries = cr.query(EntriesListFragment.this.mCurrentUri, new String[]{BaseColumns._ID}, where, null, null);
                    cr.update(EntriesListFragment.this.mCurrentUri, FeedData.getReadContentValues(), where, null);
                }
            }.start();

            // If we are on "all items" uri, we can remove the notification here
            if (this.mCurrentUri != null && Constants.NOTIF_MGR != null && (EntryColumns.CONTENT_URI.equals(this.mCurrentUri) || EntryColumns.UNREAD_ENTRIES_CONTENT_URI.equals(mCurrentUri))) {
                Constants.NOTIF_MGR.cancel(0);
            }
        }
    }

    private void startRefresh() {
        if (!PrefUtils.getBoolean(PrefUtils.IS_REFRESHING, false)) {
            if (mCurrentUri != null && FeedDataContentProvider.URI_MATCHER.match(mCurrentUri) == FeedDataContentProvider.URI_ENTRIES_FOR_FEED) {
                getActivity().startService(new Intent(getActivity(), FetcherService.class).setAction(FetcherService.ACTION_REFRESH_FEEDS).putExtra(Constants.FEED_ID,
                        mCurrentUri.getPathSegments().get(1)));
            } else {
                getActivity().startService(new Intent(getActivity(), FetcherService.class).setAction(FetcherService.ACTION_REFRESH_FEEDS));
            }
        }

        refreshSwipeProgress();
    }

    public Uri getUri() {
        return mOriginalUri;
    }

    public void setData(Uri uri, boolean showFeedInfo) {
        setData(uri, showFeedInfo, false);
    }

    private void setData(Uri uri, boolean showFeedInfo, boolean isSearchUri) {
        mCurrentUri = uri;
        if (!isSearchUri) {
            mOriginalUri = mCurrentUri;
        }

        mShowFeedInfo = showFeedInfo;

        mEntriesCursorAdapter = new EntriesCursorAdapter(getActivity(), mCurrentUri, Constants.EMPTY_CURSOR, mShowFeedInfo);
        setListAdapter(mEntriesCursorAdapter);

        mListDisplayDate = new Date().getTime();
        if (mCurrentUri != null) {
            restartLoaders();
        }
        refreshUI();
    }

    private void restartLoaders() {
        LoaderManager loaderManager = getLoaderManager();

        //HACK: 2 times to workaround a hard-to-reproduce bug with non-refreshing loaders...
        loaderManager.restartLoader(ENTRIES_LOADER_ID, null, mEntriesLoader);
        loaderManager.restartLoader(NEW_ENTRIES_NUMBER_LOADER_ID, null, mEntriesNumberLoader);

        loaderManager.restartLoader(ENTRIES_LOADER_ID, null, mEntriesLoader);
        loaderManager.restartLoader(NEW_ENTRIES_NUMBER_LOADER_ID, null, mEntriesNumberLoader);
    }

    private void refreshUI() {
        if (mNewEntriesNumber > 0) {
            mRefreshListBtn.setText(getResources().getQuantityString(R.plurals.number_of_new_entries, mNewEntriesNumber, mNewEntriesNumber));
            mRefreshListBtn.setVisibility(View.VISIBLE);
        } else {
            mRefreshListBtn.setVisibility(View.GONE);
        }
    }

    private void refreshSwipeProgress() {
        if (PrefUtils.getBoolean(PrefUtils.IS_REFRESHING, false)) {
            showSwipeProgress();
        } else {
            hideSwipeProgress();
        }
    }

    private class SwipeGestureListener extends SimpleOnGestureListener implements OnTouchListener {
        static final int SWIPE_MIN_DISTANCE = 120;
        static final int SWIPE_MAX_OFF_PATH = 150;
        static final int SWIPE_THRESHOLD_VELOCITY = 150;

        private final GestureDetector mGestureDetector;

        public SwipeGestureListener(Context context) {
            mGestureDetector = new GestureDetector(context, this);
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (mListView != null && e1 != null && e2 != null && Math.abs(e1.getY() - e2.getY()) <= SWIPE_MAX_OFF_PATH && Math.abs(velocityX) >= SWIPE_THRESHOLD_VELOCITY) {
                long id = mListView.pointToRowId(Math.round(e2.getX()), Math.round(e2.getY()));
                int position = mListView.pointToPosition(Math.round(e2.getX()), Math.round(e2.getY()));
                View view = mListView.getChildAt(position - mListView.getFirstVisiblePosition());

                if (view != null) {
                    // Just click on views, the adapter will do the real stuff
                    if (e1.getX() - e2.getX() > SWIPE_MIN_DISTANCE) {
                        mEntriesCursorAdapter.toggleReadState(id, view);
                    } else if (e2.getX() - e1.getX() > SWIPE_MIN_DISTANCE) {
                        mEntriesCursorAdapter.toggleFavoriteState(id, view);
                    }

                    // Just simulate a CANCEL event to remove the item highlighting
                    mListView.post(new Runnable() { // In a post to avoid a crash on 4.0.x
                        @Override
                        public void run() {
                            MotionEvent motionEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0);
                            mListView.dispatchTouchEvent(motionEvent);
                            motionEvent.recycle();
                        }
                    });
                    return true;
                }
            }

            return super.onFling(e1, e2, velocityX, velocityY);
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            return mGestureDetector.onTouchEvent(event);
        }
    }
}
