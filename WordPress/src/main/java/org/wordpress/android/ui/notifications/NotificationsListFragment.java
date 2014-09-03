package org.wordpress.android.ui.notifications;

import android.app.ListFragment;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.simperium.client.Bucket;
import com.simperium.client.BucketObject;
import com.simperium.client.BucketObjectMissingException;

import org.wordpress.android.R;
import org.wordpress.android.models.Note;
import org.wordpress.android.ui.notifications.utils.SimperiumUtils;
import org.wordpress.android.util.DisplayUtils;
import org.wordpress.android.util.StringUtils;
import org.wordpress.android.util.ToastUtils;
import org.wordpress.android.util.ptr.PullToRefreshHelper;

import javax.annotation.Nonnull;

import uk.co.senab.actionbarpulltorefresh.library.PullToRefreshLayout;

public class NotificationsListFragment extends ListFragment implements Bucket.Listener<Note> {
    private PullToRefreshHelper mFauxPullToRefreshHelper;
    private NotesAdapter mNotesAdapter;
    private OnNoteClickListener mNoteClickListener;
    private boolean mShouldLoadFirstNote;
    private String mSelectedNoteId;

    Bucket<Note> mBucket;

    /**
     * For responding to tapping of notes
     */
    public interface OnNoteClickListener {
        public void onClickNote(Note note, int position);
    }

    @Override
    public View onCreateView(@Nonnull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.notifications_fragment_notes_list, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // setup the initial notes adapter, starts listening to the bucket
        mBucket = SimperiumUtils.getNotesBucket();

        ListView listView = getListView();
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setBackgroundColor(getResources().getColor(R.color.white));
        if (DisplayUtils.isLandscapeTablet(getActivity())) {
            listView.setChoiceMode(AbsListView.CHOICE_MODE_SINGLE);
        }

        if (mBucket != null && mNotesAdapter == null) {
            mNotesAdapter = new NotesAdapter(getActivity(), mBucket);
            setListAdapter(mNotesAdapter);
        } else if (mBucket == null) {
            ToastUtils.showToast(getActivity(), R.string.error_refresh_notifications);
        }

        // Set empty text if no notifications
        TextView textview = (TextView) listView.getEmptyView();
        if (textview != null) {
            textview.setText(getText(R.string.notifications_empty_list));
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        initPullToRefreshHelper();
        mFauxPullToRefreshHelper.registerReceiver(getActivity());
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshNotes();

        // start listening to bucket change events
        mBucket.addListener(this);
    }

    @Override
    public void onPause() {
        // unregister the listener
        mBucket.removeListener(this);

        super.onPause();
    }

    @Override
    public void onDestroy() {
        // Close Simperium cursor
        mNotesAdapter.closeCursor();

        mFauxPullToRefreshHelper.unregisterReceiver(getActivity());
        super.onDestroyView();
    }

    private void initPullToRefreshHelper() {
        mFauxPullToRefreshHelper = new PullToRefreshHelper(
                getActivity(),
                (PullToRefreshLayout) getActivity().findViewById(R.id.ptr_layout),
                new PullToRefreshHelper.RefreshListener() {
                    @Override
                    public void onRefreshStarted(View view) {
                        // Show a fake refresh animation for a few seconds
                        new Handler().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (isAdded()) {
                                    mFauxPullToRefreshHelper.setRefreshing(false);
                                }
                            }
                        }, 2000);
                    }
                }, LinearLayout.class
        );
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        if (!isAdded()) return;

        Note note = mNotesAdapter.getNote(position);
        if (note != null && mNoteClickListener != null) {
            mNoteClickListener.onClickNote(note, position);
        }
    }

    public void setSelectedNoteId(String selectedNoteId) {
        mSelectedNoteId = selectedNoteId;
    }

    public void setOnNoteClickListener(OnNoteClickListener listener) {
        mNoteClickListener = listener;
    }

    protected void updateLastSeenTime() {
        // set the timestamp to now
        try {
            if (mNotesAdapter != null && mNotesAdapter.getCount() > 0 && SimperiumUtils.getMetaBucket() != null) {
                Note newestNote = mNotesAdapter.getNote(0);
                BucketObject meta = SimperiumUtils.getMetaBucket().get("meta");
                if (meta != null && newestNote != null) {
                    meta.setProperty("last_seen", newestNote.getTimestamp());
                    meta.save();
                }
            }
        } catch (BucketObjectMissingException e) {
            // try again later, meta is created by wordpress.com
        }
    }

    public void refreshNotes() {
        if (!isAdded() || mNotesAdapter == null) {
            return;
        }

        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mNotesAdapter.setShouldHighlightRows(DisplayUtils.isLandscapeTablet(getActivity()));
                mNotesAdapter.reloadNotes();
                updateLastSeenTime();

                if (DisplayUtils.isLandscapeTablet(getActivity()) && getListView() != null) {
                    // Select first row on a landscape tablet
                    if (mShouldLoadFirstNote) {
                        mShouldLoadFirstNote = false;
                        Note note = mNotesAdapter.getNote(0);
                        if (note != null) {
                            mNoteClickListener.onClickNote(note, 0);
                            mSelectedNoteId = note.getId();
                        }
                    }

                    if (mSelectedNoteId != null) {
                        new HighlightSelectedNoteTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                    }
                }
            }
        });
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (outState.isEmpty()) {
            outState.putBoolean("bug_19917_fix", true);
        }

        super.onSaveInstanceState(outState);
    }

    public void setShouldLoadFirstNote(boolean shouldLoad) {
        mShouldLoadFirstNote = shouldLoad;
    }


    /**
     * Simperium bucket listener methods
     */
    @Override
    public void onSaveObject(Bucket<Note> bucket, Note object) {
        refreshNotes();
    }

    @Override
    public void onDeleteObject(Bucket<Note> bucket, Note object) {
        refreshNotes();
    }

    @Override
    public void onChange(Bucket<Note> bucket, Bucket.ChangeType type, String key) {
        refreshNotes();
    }

    @Override
    public void onBeforeUpdateObject(Bucket<Note> noteBucket, Note note) {
        //noop
    }

    // Retrieve the index for the selected note and set it to be highlighted (for landscape tablets only)
    private class HighlightSelectedNoteTask extends AsyncTask<Void, Void, Integer> {

        @Override
        protected Integer doInBackground(Void... params) {
            if (TextUtils.isEmpty(mSelectedNoteId) || mNotesAdapter == null) {
                return ListView.INVALID_POSITION;
            }

            Bucket.ObjectCursor<Note> cursor = (Bucket.ObjectCursor<Note>) mNotesAdapter.getCursor();
            if (cursor != null) {
                for (int i = 0; i < cursor.getCount(); i++) {
                    cursor.moveToPosition(i);
                    String noteSimperiumId = cursor.getSimperiumKey();
                    if (noteSimperiumId != null && noteSimperiumId.equals(mSelectedNoteId)) {
                        return i;
                    }
                }
            }

            return ListView.INVALID_POSITION;
        }

        @Override
        protected void onPostExecute(Integer noteListPosition) {
            if (isAdded() && noteListPosition != ListView.INVALID_POSITION && getListView() != null) {
                getListView().setItemChecked(noteListPosition, true);
            }

            mSelectedNoteId = null;
        }
    }

}
