package fr.free.nrw.commons.media;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentStatePagerAdapter;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.MenuItemCompat;
import android.support.v4.view.ViewPager;
import android.support.v7.widget.ShareActionProvider;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import javax.inject.Inject;

import dagger.android.support.DaggerFragment;
import fr.free.nrw.commons.CommonsApplication;
import fr.free.nrw.commons.Media;
import fr.free.nrw.commons.R;
import fr.free.nrw.commons.auth.SessionManager;
import fr.free.nrw.commons.contributions.Contribution;
import fr.free.nrw.commons.contributions.ContributionsActivity;
import fr.free.nrw.commons.mwapi.EventLog;
import fr.free.nrw.commons.mwapi.MediaWikiApi;

public class MediaDetailPagerFragment extends DaggerFragment implements ViewPager.OnPageChangeListener {

    public interface MediaDetailProvider {
        Media getMediaAtPosition(int i);

        int getTotalMediaCount();

        void notifyDatasetChanged();

        void registerDataSetObserver(DataSetObserver observer);

        void unregisterDataSetObserver(DataSetObserver observer);
    }

    @Inject MediaWikiApi mwApi;
    @Inject SessionManager sessionManager;

    private ViewPager pager;
    private Boolean editable;

    public MediaDetailPagerFragment() {
        this(false);
    }

    @SuppressLint("ValidFragment")
    public MediaDetailPagerFragment(Boolean editable) {
        this.editable = editable;
    }

    //FragmentStatePagerAdapter allows user to swipe across collection of images (no. of images undetermined)
    private class MediaDetailAdapter extends FragmentStatePagerAdapter {

        public MediaDetailAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public Fragment getItem(int i) {
            if (i == 0) {
                // See bug https://code.google.com/p/android/issues/detail?id=27526
                pager.postDelayed(() -> getActivity().supportInvalidateOptionsMenu(), 5);
            }
            return MediaDetailFragment.forMedia(i, editable);
        }

        @Override
        public int getCount() {
            return ((MediaDetailProvider) getActivity()).getTotalMediaCount();
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_media_detail_pager, container, false);
        pager = (ViewPager) view.findViewById(R.id.mediaDetailsPager);
        pager.addOnPageChangeListener(this);

        final MediaDetailAdapter adapter = new MediaDetailAdapter(getChildFragmentManager());

        if (savedInstanceState != null) {
            final int pageNumber = savedInstanceState.getInt("current-page");
            // Adapter doesn't seem to be loading immediately.
            // Dear God, please forgive us for our sins
            view.postDelayed(() -> {
                pager.setAdapter(adapter);
                pager.setCurrentItem(pageNumber, false);
                getActivity().supportInvalidateOptionsMenu();
                adapter.notifyDataSetChanged();
            }, 100);
        } else {
            pager.setAdapter(adapter);
        }
        return view;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("current-page", pager.getCurrentItem());
        outState.putBoolean("editable", editable);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            editable = savedInstanceState.getBoolean("editable");
        }
        setHasOptionsMenu(true);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        MediaDetailProvider provider = (MediaDetailProvider) getActivity();
        Media m = provider.getMediaAtPosition(pager.getCurrentItem());
        switch (item.getItemId()) {
            case R.id.menu_share_current_image:
                // Share - this is just logs it, intent set in onCreateOptionsMenu, around line 252
                EventLog.schema(CommonsApplication.EVENT_SHARE_ATTEMPT, getContext().getApplicationContext(), mwApi)
                        .param("username", sessionManager.getCurrentAccount().name)
                        .param("filename", m.getFilename())
                        .log();
                return true;
            case R.id.menu_browser_current_image:
                // View in browser
                Intent viewIntent = new Intent();
                viewIntent.setAction(Intent.ACTION_VIEW);
                viewIntent.setData(m.getFilePageTitle().getMobileUri());
                startActivity(viewIntent);
                return true;
            case R.id.menu_download_current_image:
                // Download
                downloadMedia(m);
                return true;
            case R.id.menu_retry_current_image:
                // Retry
                ((ContributionsActivity) getActivity()).retryUpload(pager.getCurrentItem());
                getActivity().getSupportFragmentManager().popBackStack();
                return true;
            case R.id.menu_cancel_current_image:
                // todo: delete image
                ((ContributionsActivity) getActivity()).deleteUpload(pager.getCurrentItem());
                getActivity().getSupportFragmentManager().popBackStack();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Start the media file downloading to the local SD card/storage.
     * The file can then be opened in Gallery or other apps.
     *
     * @param m Media file to download
     */
    private void downloadMedia(Media m) {
        String imageUrl = m.getImageUrl(),
                fileName = m.getFilename();
        // Strip 'File:' from beginning of filename, we really shouldn't store it
        fileName = fileName.replaceFirst("^File:", "");
        Uri imageUri = Uri.parse(imageUrl);

        DownloadManager.Request req = new DownloadManager.Request(imageUri);
        //These are not the image title and description fields, they are download descs for notifications
        req.setDescription(getString(R.string.app_name));
        req.setTitle(m.getDisplayTitle());
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

        // Modern Android updates the gallery automatically. Yay!
        req.allowScanningByMediaScanner();
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !(ContextCompat.checkSelfPermission(getContext(),
                        Manifest.permission.READ_EXTERNAL_STORAGE) ==
                        PackageManager.PERMISSION_GRANTED)) {
            Snackbar.make(getView(), R.string.storage_permission_rationale,
                    Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.ok, view -> ActivityCompat.requestPermissions(getActivity(),
                            new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1)).show();
        } else {
            final DownloadManager manager = (DownloadManager) getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
            manager.enqueue(req);
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        if (!editable) { // Disable menu options for editable views
            menu.clear(); // see http://stackoverflow.com/a/8495697/17865
            inflater.inflate(R.menu.fragment_image_detail, menu);
            if (pager != null) {
                MediaDetailProvider provider = (MediaDetailProvider) getActivity();
                Media m = provider.getMediaAtPosition(pager.getCurrentItem());
                if (m != null) {
                    // Enable default set of actions, then re-enable different set of actions only if it is a failed contrib
                    menu.findItem(R.id.menu_retry_current_image).setEnabled(false).setVisible(false);
                    menu.findItem(R.id.menu_cancel_current_image).setEnabled(false).setVisible(false);
                    menu.findItem(R.id.menu_browser_current_image).setEnabled(true).setVisible(true);
                    menu.findItem(R.id.menu_share_current_image).setEnabled(true).setVisible(true);
                    menu.findItem(R.id.menu_download_current_image).setEnabled(true).setVisible(true);

                    // Set ShareActionProvider Intent
                    ShareActionProvider mShareActionProvider = (ShareActionProvider) MenuItemCompat.getActionProvider(menu.findItem(R.id.menu_share_current_image));
                    // On some phones null is returned for some reason:
                    // https://github.com/commons-app/apps-android-commons/issues/413
                    if (mShareActionProvider != null) {
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("text/plain");
                        shareIntent.putExtra(Intent.EXTRA_TEXT,
                                m.getDisplayTitle() + " \n" + m.getFilePageTitle().getCanonicalUri());
                        mShareActionProvider.setShareIntent(shareIntent);
                    }

                    if (m instanceof Contribution) {
                        Contribution c = (Contribution) m;
                        switch (c.getState()) {
                            case Contribution.STATE_FAILED:
                                menu.findItem(R.id.menu_retry_current_image).setEnabled(true).setVisible(true);
                                menu.findItem(R.id.menu_cancel_current_image).setEnabled(true).setVisible(true);
                                menu.findItem(R.id.menu_browser_current_image).setEnabled(false).setVisible(false);
                                menu.findItem(R.id.menu_share_current_image).setEnabled(false).setVisible(false);
                                menu.findItem(R.id.menu_download_current_image).setEnabled(false).setVisible(false);
                                break;
                            case Contribution.STATE_IN_PROGRESS:
                            case Contribution.STATE_QUEUED:
                                menu.findItem(R.id.menu_retry_current_image).setEnabled(false).setVisible(false);
                                menu.findItem(R.id.menu_cancel_current_image).setEnabled(false).setVisible(false);
                                menu.findItem(R.id.menu_browser_current_image).setEnabled(false).setVisible(false);
                                menu.findItem(R.id.menu_share_current_image).setEnabled(false).setVisible(false);
                                menu.findItem(R.id.menu_download_current_image).setEnabled(false).setVisible(false);
                                break;
                            case Contribution.STATE_COMPLETED:
                                // Default set of menu items works fine. Treat same as regular media object
                                break;
                        }
                    }
                }
            }
        }
    }

    public void showImage(int i) {
        pager.setCurrentItem(i);
    }

    @Override
    public void onPageScrolled(int i, float v, int i2) {
        getActivity().supportInvalidateOptionsMenu();
    }

    @Override
    public void onPageSelected(int i) {
    }

    @Override
    public void onPageScrollStateChanged(int i) {

    }
}