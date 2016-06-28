package org.wordpress.android.ui.menus;

import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.wordpress.android.R;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.wordpress.android.WordPress;
import org.wordpress.android.datasets.MenuLocationTable;
import org.wordpress.android.datasets.MenuTable;
import org.wordpress.android.models.MenuItemModel;
import org.wordpress.android.models.MenuLocationModel;
import org.wordpress.android.models.MenuModel;
import org.wordpress.android.networking.menus.MenusDataModeler;
import org.wordpress.android.networking.menus.MenusRestWPCom;
import org.wordpress.android.ui.EmptyViewMessageType;
import org.wordpress.android.ui.menus.items.MenuItemEditorFactory;
import org.wordpress.android.ui.menus.views.MenuAddEditRemoveView;
import org.wordpress.android.ui.menus.views.MenuItemAdapter;
import org.wordpress.android.ui.menus.views.MenuItemsView;
import org.wordpress.android.ui.prefs.SiteSettingsInterface;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.CollectionUtils;
import org.wordpress.android.util.NetworkUtils;
import org.wordpress.android.util.ToastUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MenusFragment extends Fragment implements MenuItemAdapter.MenuItemInteractions, SiteSettingsInterface.SiteSettingsListener {
    private static final int BASE_DISPLAY_COUNT_MENUS = -2;

    private SiteSettingsInterface mSiteSettings;
    private boolean mUndoPressed = false;
    private MenusRestWPCom mRestWPCom;
    private MenuAddEditRemoveView mAddEditRemoveControl;
    private int mCurrentLoadRequestId;
    private int mCurrentCreateRequestId;
    private int mCurrentUpdateRequestId;
    private int mCurrentDeleteRequestId;
    private MenuModel mMenuDeletedHolder;
    private boolean mIsUpdatingMenus;
    private TextView mEmptyView;
    private LinearLayout mSpinnersLayout;
    private MenusSpinner mMenuLocationsSpinner;
    private MenusSpinner mMenusSpinner;
    private MenuModel mCurrentMenuForLocation;
    private MenuItemsView mItemsView;
    private int mCurrentMenuItemBeingEdited = -1;
    private boolean mIsNewItem = false;
    private EditMenuItemDialog mDialog;
    private Toolbar mToolbar;
    private List<MenuItemModel> mOriginalFlattenedMenuItems;
    private int mOriginalMenuSelectionIdx;


    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        mSiteSettings = SiteSettingsInterface.getInterface(getActivity(), WordPress.getCurrentBlog(), this);
        mSiteSettings.init(true);

        mRestWPCom = new MenusRestWPCom(new MenusRestWPCom.MenusListener() {
            @Override public Context getContext() {
                return getActivity();
            }

            @Override public long getSiteId() {
                return Long.valueOf(WordPress.getCurrentRemoteBlogId());
            }

            @Override public void onMenuCreated(int requestId, @NonNull final MenuModel menu) {
                // make sure we don't intercept another create request
                if (requestId != mCurrentCreateRequestId) return;

                // TODO: will local-only changes be affected when refreshing menus?
                //save new menu to local DB here
                saveMenuBackground(menu);

                if (!isAdded()) return;

                // notify user that menu was created successfully
                ToastUtils.showToast(getActivity(), getString(R.string.menus_menu_created));
                // add this newly created menu to the spinner
                addMenuToCurrentList(menu);
                // enable the action UI elements
                mAddEditRemoveControl.setActive(true);
                // TODO: should updating the menu be handled here or in MenusRestWPCom?
                //now update the menu so the menu location will be saved server-side
                mCurrentUpdateRequestId = mRestWPCom.updateMenu(menu);
            }

            @Override public void onMenusReceived(int requestId, final List<MenuModel> menus, final List<MenuLocationModel> locations) {
                // make sure we don't intercept another load request
                if (requestId != mCurrentLoadRequestId) return;
                boolean bSpinnersUpdated = false;
                if (locations != null) {
                    if (!CollectionUtils.areListsEqual(locations, mMenuLocationsSpinner.getItems())) {
                        // update Menu Locations spinner
                        mMenuLocationsSpinner.setItems(locations, 0);
                        bSpinnersUpdated = true;
                    }
                }

                if (menus != null) {
                    if (!CollectionUtils.areListsEqual(menus, mMenusSpinner.getItems())) {
                        //make a copy of the menu array and strip off the default and add menu "menus"
                        final List<MenuModel> userMenusOnly = getUserMenusOnly(menus);

                        //save menus to local DB here
                        new AsyncTask<Void, Void, Boolean>() {
                            @Override protected Boolean doInBackground(Void... params) {
                                MenuTable.saveMenus(userMenusOnly);
                                MenuLocationTable.saveMenuLocations(locations);
                                return null;
                            }

                            @Override protected void onPostExecute(Boolean result) { }
                        }.execute();

                        if (locations != null) {
                            addSpecialMenus(locations.get(0), menus);
                        }
                        // update Menus spinner
                        mMenusSpinner.setItems(menus, BASE_DISPLAY_COUNT_MENUS);
                        bSpinnersUpdated = true;
                    }
                }

                if (!isAdded()) {
                    return;
                }

                if (bSpinnersUpdated) {
                    hideEmptyView();
                }
                mIsUpdatingMenus = false;
            }

            @Override public void onMenuDeleted(int requestId, MenuModel menu, boolean deleted) {
                // make sure we don't intercept another delete request
                if (requestId != mCurrentDeleteRequestId) return;
                //delete menu from local DB here
                final long menuId = menu.menuId;
                new AsyncTask<Void, Void, Boolean>() {
                    @Override protected Boolean doInBackground(Void... params) {
                        MenuTable.deleteMenu(menuId);
                        return null;
                    }

                    @Override protected void onPostExecute(Boolean result) { }
                }.execute();

                if (!isAdded()) return;

                if (deleted) {
                    Toast.makeText(getActivity(), getString(R.string.menus_menu_deleted), Toast.LENGTH_SHORT).show();
                    //delete menu from Spinner here
                    if (mMenusSpinner.getItems() != null) {
                        if (mMenusSpinner.getItems().remove(menu)) {
                            mMenusSpinner.setItems(mMenusSpinner.getItems(), BASE_DISPLAY_COUNT_MENUS);
                        }
                    }
                }
                else {
                    Toast.makeText(getActivity(), getString(R.string.could_not_delete_menu), Toast.LENGTH_SHORT).show();
                }

            }
            @Override public void onMenuUpdated(int requestId, @NonNull final MenuModel menu) {
                // make sure we don't intercept another update request
                if (requestId != mCurrentUpdateRequestId) return;
                //update menu in local DB here
                saveMenuBackground(menu);

                if (!isAdded()) return;

                ToastUtils.showToast(getActivity(), getString(R.string.menus_menu_updated));

                //update menu in Spinner here
                if (mMenusSpinner.getItems() != null) {
                    int selectedPos = -1;
                    for (int i=0; i < mMenusSpinner.getItems().size(); i++) {
                        MenuModel item = (MenuModel) mMenusSpinner.getItems().get(i);
                        if (item != null && item.menuId == menu.menuId) {
                            selectedPos = i;
                            item.name = menu.name;
                            item.details = menu.details;
                            item.locations = menu.locations;
                            item.menuItems = menu.menuItems;
                            break;
                        }
                    }

                    //only re-set the spinner if it's not a special menu that we have just updated.
                    //otherwise, we would be changing the actual selection (i.e. user selected No Menu, we updated the
                    //last rememebred real menu which is returned in this callback, and we'd be setting
                    //the spinner to show that real menu instead of the selected No Menu).
                    if (!((MenuModel) mMenusSpinner.getSelectedItem()).isSpecialMenu()){
                        //I have to re-set items on the Spinner so not only the adapter will change but also the textview
                        //within the Spinner control - note that if a menu has been updated, it is currently being shown and
                        //selected within the Spinner control view, so it needs to change to reflect the update as well.
                        if (selectedPos >= 0) {
                            mMenusSpinner.setItems(mMenusSpinner.getItems(), BASE_DISPLAY_COUNT_MENUS);
                            mMenusSpinner.setSelection(selectedPos);
                        }
                    }
                }
            }

            @Override
            public void onErrorResponse(int requestId, MenusRestWPCom.REST_ERROR error) {
                // load menus
                if (error == MenusRestWPCom.REST_ERROR.FETCH_ERROR) {
                    if (mMenuLocationsSpinner.getCount() == 0 || mMenusSpinner.getCount() == 0) {
                        Toast.makeText(getActivity(), getString(R.string.could_not_load_menus), Toast.LENGTH_SHORT).show();
                        updateEmptyView(EmptyViewMessageType.NO_CONTENT);
                    } else {
                        Toast.makeText(getActivity(), getString(R.string.could_not_refresh_menus), Toast.LENGTH_SHORT).show();
                    }
                    mIsUpdatingMenus = false;
                }
                else
                if (error == MenusRestWPCom.REST_ERROR.CREATE_ERROR) {
                    Toast.makeText(getActivity(), getString(R.string.could_not_create_menu), Toast.LENGTH_SHORT).show();
                }
                else
                if (error == MenusRestWPCom.REST_ERROR.UPDATE_ERROR) {
                    Toast.makeText(getActivity(), getString(R.string.could_not_update_menu), Toast.LENGTH_SHORT).show();
                }
                else
                if (error == MenusRestWPCom.REST_ERROR.DELETE_ERROR) {
                    Toast.makeText(getActivity(), getString(R.string.could_not_delete_menu), Toast.LENGTH_SHORT).show();
                    if (requestId == mCurrentDeleteRequestId && mMenuDeletedHolder != null) {
                        //restore the menu item in the spinner list
                        addMenuToCurrentList(mMenuDeletedHolder);
                    }
                }
            }
        });

    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.menus_fragment, container, false);

        mToolbar = (Toolbar) view.findViewById(R.id.toolbar);

        setupToolbar();

        mAddEditRemoveControl = (MenuAddEditRemoveView) view.findViewById(R.id.menu_add_edit_remove_view);
        mAddEditRemoveControl.setMenuActionListener(new MenuAddEditRemoveView.MenuAddEditRemoveActionListener() {

            @Override
            public void onMenuCreate(MenuModel menu) {
                if (!isAdded() || !NetworkUtils.checkConnection(getActivity()) ) return;

                //set the menu's current configuration now
                MenuModel menuToUpdate = setMenuLocation(menu);

                //add the menu items
                menuToUpdate.menuItems = MenusDataModeler.inflateMenuItemModelList(mItemsView.getCurrentMenuItems());

                mCurrentCreateRequestId = mRestWPCom.createMenu(menuToUpdate);
            }

            @Override
            public boolean onMenuDelete(final MenuModel menu) {
                if (!isAdded() || !NetworkUtils.checkConnection(getActivity()) ) {
                    //restore the Add/Edit/Remove control
                    mAddEditRemoveControl.setMenu(menu);
                    return false;
                }

                //delete menu from Spinner here
                if (mMenusSpinner.getItems() != null) {
                    if (mMenusSpinner.getItems().remove(menu)) {
                        mMenusSpinner.setItems(mMenusSpinner.getItems(), BASE_DISPLAY_COUNT_MENUS);
                        mMenusSpinner.setSelection(-1, true);
                    }
                }

                View.OnClickListener undoListener = new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mUndoPressed = true;
                        // user undid the trash action, so reset the control to whatever it had
                        mAddEditRemoveControl.setMenu(menu);
                        //restore the menu item in the spinner list
                        addMenuToCurrentList(menu);
                    }
                };

                View snackbarView = getView();
                if (snackbarView == null) return true;

                Snackbar snackbar = Snackbar.make(snackbarView, getString(R.string.menus_menu_deleted), Snackbar.LENGTH_LONG)
                        .setAction(R.string.undo, undoListener);

                // wait for the undo snackbar to disappear before actually deleting the menu
                snackbar.setCallback(new Snackbar.Callback() {
                    @Override
                    public void onDismissed(Snackbar snackbar, int event) {
                        super.onDismissed(snackbar, event);
                        if (mUndoPressed) {
                            mUndoPressed = false;
                            return;
                        }

                        mMenuDeletedHolder = menu;
                        mCurrentDeleteRequestId = mRestWPCom.deleteMenu(menu);
                    }
                });

                snackbar.show();

                return true;
            }

            @Override
            public void onMenuUpdate(MenuModel menu) {
                if (!isAdded() || !NetworkUtils.checkConnection(getActivity())) return;

                //set the menu's current configuration now
                MenuModel menuToUpdate = setMenuLocation(menu);

                //add the menu items
                menuToUpdate.menuItems = MenusDataModeler.inflateMenuItemModelList(mItemsView.getCurrentMenuItems());

                mCurrentUpdateRequestId = mRestWPCom.updateMenu(menuToUpdate);
            }
        });

        mMenuLocationsSpinner = (MenusSpinner) view.findViewById(R.id.menu_locations_spinner);
        mMenusSpinner = (MenusSpinner) view.findViewById(R.id.selected_menu_spinner);
        mEmptyView = (TextView) view.findViewById(R.id.empty_view);
        mSpinnersLayout = (LinearLayout) view.findViewById(R.id.spinner_group);

        mMenusSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {

                if (mOriginalMenuSelectionIdx == position) return;

                if (mOriginalFlattenedMenuItems != null) {
                    if (!mOriginalFlattenedMenuItems.equals(mItemsView.getAdapter().getCurrentMenuItems())) {
                        //the collections are different
                        mMenusSpinner.setSelection(mOriginalMenuSelectionIdx);
                        showAlertDialog(position);
                        return;
                    }
                }

                changeMenuSpinnerSelection(position);

            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        mMenuLocationsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                //auto-select the first available menu for this location
                boolean bFound = false;
                List<MenuModel> menus = mMenusSpinner.getItems();
                if (menus != null && menus.size() > 0 && mMenuLocationsSpinner.getItems() != null &&
                        mMenuLocationsSpinner.getItems().size() > 0) {
                    MenuLocationModel menuLocationSelected = (MenuLocationModel)
                            mMenuLocationsSpinner.getItems().get(position);

                    addSpecialMenus(menuLocationSelected, menus);
                    //we need to re-set (re-create) the spinner adapter in order to make the selection be re-drawn
                    //otherwise if items have changed but the selection remains the same, changes don' get rendered
                    mMenusSpinner.setItems(menus, BASE_DISPLAY_COUNT_MENUS);

                    for (int i = 0; i < menus.size() && !bFound; i++) {
                        MenuModel menu = menus.get(i);
                        if (menu.locations != null) {
                            for (MenuLocationModel menuLocation : menu.locations) {
                                if (menuLocationSelected.equals(menuLocation)) {
                                    //set this one and break;
                                    mMenusSpinner.setSelection(i);
                                    mCurrentMenuForLocation = menu;
                                    bFound = true;
                                    break;
                                }
                            }
                        }
                    }

                    if (!bFound) {
                        // select the Default Menu
                        mCurrentMenuForLocation = (MenuModel) mMenusSpinner.getItems().get(0);
                        mMenusSpinner.setSelection(0);
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        mItemsView = (MenuItemsView) view.findViewById(R.id.menu_items_recyclerview);
        mItemsView.setListener(this);

        return view;
    }

    private void changeMenuSpinnerSelection(int position) {
        if (mMenusSpinner.getItems().size() == (position + 1)) {
            //clicked on "add new menu"

            //for new menus, given we might be off line, it' best to not try creating a default menu right away
            // (as opposed to how the calypso web does this)
            //but wait for the user to enter a name for the menu and click SAVE on the AddRemoveEdit view control
            //that's why we set the menu within the control to null
            mAddEditRemoveControl.setMenu(null);
            mItemsView.updateEmptyView(EmptyViewMessageType.NO_CONTENT);

        } else {
            MenuModel model = (MenuModel) mMenusSpinner.getItems().get(position);
            mAddEditRemoveControl.setMenu(model);

            if (!model.isSpecialMenu()) {
                mCurrentMenuForLocation = model;
            }

            //show items
            mItemsView.hideEmptyView();
            mItemsView.setMenu(model);
            mOriginalFlattenedMenuItems = copyFlattenedMenuItemList(mItemsView.getCurrentMenuItems());
            mOriginalMenuSelectionIdx = position;
        }
    }

    private List<MenuItemModel> copyFlattenedMenuItemList(List<MenuItemModel> origList) {
        ArrayList<MenuItemModel> copyList = new ArrayList<>();
        copyList.addAll(origList);
        return copyList;
    }

    private void setupToolbar() {
        if (mToolbar == null || !isAdded()) return;

        // add back arrow listener
        mToolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                //TODO check if dirty

            }
        });
    }

    @Override
    public void onItemClick(MenuItemModel item, int position) {
        if (item != null) {
            showEditorDialog(item);
            mIsNewItem = false;
            mCurrentMenuItemBeingEdited = position;
        }
    }

    @Override
    public void onCancelClick() {
        // no op
    }

    @Override
    public void onAddClick(int position, MenuItemAdapter.ItemAddPosition where) {
        //the user wants to add a new item <where: above/below/tochildren> relative to <position>

        List<MenuItemModel> items = mItemsView.getCurrentMenuItems();
        int originalItemFlattenedLevel = 0;
        boolean emptyList = true;
        if (items.size() > 0) {
            MenuItemModel originalItem = items.get(position);
            originalItemFlattenedLevel = originalItem.flattenedLevel;
            emptyList = false;
        }
        final MenuItemModel newItem = new MenuItemModel();
        newItem.name = getString(R.string.menus_item_new_item);
        newItem.flattenedLevel = originalItemFlattenedLevel;
        newItem.type = MenuItemEditorFactory.ITEM_TYPE.POST.name().toLowerCase(); //default type: POST
        newItem.calculateCustomType();
        newItem.typeFamily = "post_type";
        newItem.typeLabel = MenuItemEditorFactory.ITEM_TYPE.POST.name();
        switch (where) {
            case TO_CHILDREN:
                newItem.flattenedLevel++;
            case BELOW :
                if (position == items.size() - 1) {
                    items.add(newItem);
                    position++;
                    mItemsView.getAdapter().notifyItemInserted(items.size() - 1);
                } else {
                    items.add(position, newItem);
                    mItemsView.getAdapter().notifyItemInserted(position+1);
                }
                break;
            case ABOVE :
            default:
                items.add(position, newItem);
                mItemsView.getAdapter().notifyItemInserted(position);
                break;
        }

        mCurrentMenuItemBeingEdited = position;
        mIsNewItem = true;

        if (!emptyList) {
            //enclosed Dialog show in a delayed handler to allow animations to be appreciated
            Handler hdlr = new Handler();
            hdlr.postDelayed(new Runnable() {
                @Override
                public void run() {
                    showEditorDialog(newItem);
                }
            }, 350);
        } else {
            showEditorDialog(newItem);
        }
    }

    private ArrayList<String> getItemTypes() {
        String[] typeArray = getResources().getStringArray(R.array.menu_item_type_spinner_entries);
        ArrayList<String> typeList = new ArrayList<>(Arrays.asList(typeArray));
        if (mSiteSettings.getTestimonialsEnabled()) {
            typeList.add(typeList.size(), getString(R.string.menu_item_type_testimonial));
        }
        return typeList;
    }

    private void showEditorDialog(MenuItemModel item) {
        FragmentManager fm = getFragmentManager();
        mDialog = EditMenuItemDialog.newInstance(item, getItemTypes());
        mDialog.setTargetFragment(this, EditMenuItemDialog.EDIT_REQUEST_CODE);
        mDialog.show(fm, EditMenuItemDialog.class.getSimpleName());
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        updateMenus();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {

        if (requestCode == EditMenuItemDialog.EDIT_REQUEST_CODE) {

            if (resultCode == EditMenuItemDialog.NOT_SAVED_CODE) {
                // here delete the row if this was a new item
                if (mIsNewItem) {
                    mIsNewItem = false;
                    if (mCurrentMenuItemBeingEdited > -1) {
                        List<MenuItemModel> flattenedList = mItemsView.getAdapter().getCurrentMenuItems();
                        flattenedList.remove(mCurrentMenuItemBeingEdited);
                        mItemsView.getAdapter().notifyItemRemoved(mCurrentMenuItemBeingEdited);
                    }
                }
            } else {
                //here get the modified item from the Intent parcelable and refresh the menu item list
                //re-draw menu items
                if (mCurrentMenuItemBeingEdited > -1) {
                    MenuItemModel modifiedItem = (MenuItemModel) data.getSerializableExtra(EditMenuItemDialog.EDITED_ITEM_KEY);
                    if (modifiedItem != null) {
                        List<MenuItemModel> flattenedList = mItemsView.getAdapter().getCurrentMenuItems();
                        flattenedList.set(mCurrentMenuItemBeingEdited, modifiedItem);
                        mItemsView.getAdapter().notifyItemChanged(mCurrentMenuItemBeingEdited);
                    }
                }
            }
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    private void updateMenus() {
        if (mIsUpdatingMenus) {
            AppLog.w(AppLog.T.MENUS, "update menus task already running");
            return;
        }

        updateEmptyView(EmptyViewMessageType.LOADING);

        //immediately load/refresh whatever we have in our local db
        loadMenus();

        if (!NetworkUtils.isNetworkAvailable(getActivity())) {
            //we're offline
            return;
        }

        //also fetch latest menus from the server
        mIsUpdatingMenus = true;
        mCurrentLoadRequestId = mRestWPCom.fetchAllMenus();
    }

    private void updateEmptyView(EmptyViewMessageType emptyViewMessageType) {
        if (mEmptyView != null) {
            int stringId = 0;

            switch (emptyViewMessageType) {
                case LOADING:
                    stringId = R.string.loading;
                    break;
                case NO_CONTENT:
                    stringId = R.string.menus_spinner_empty;
                    break;
                case NETWORK_ERROR:
                    stringId = R.string.no_network_message;
                    break;
            }

            mEmptyView.setText(getText(stringId));
            mEmptyView.setVisibility(View.VISIBLE);

            if (mSpinnersLayout != null) {
                mSpinnersLayout.setVisibility(View.GONE);
            }
        }
    }

    private void hideEmptyView() {
        if (mEmptyView != null) {
            mEmptyView.setVisibility(View.GONE);
        }

        if (mSpinnersLayout != null) {
            mSpinnersLayout.setVisibility(View.VISIBLE);
        }
    }

    private List<MenuModel> getUserMenusOnly(List<MenuModel> menus){
        ArrayList<MenuModel> tmpMenus = new ArrayList<>();
        if (menus != null) {
            for (MenuModel menu : menus) {
                if (menu.siteId > 0) {
                    tmpMenus.add(menu);
                }
            }
        }
        return tmpMenus;
    }

    private void addMenuToCurrentList(MenuModel menu) {
        if (!isAdded()) return;
        List<MenuModel> menuItems = mMenusSpinner.getItems();

        if (menuItems != null) {
            // remove special menus in order to add new menu to end of list
            removeSpecialMenus(menuItems);
            //add the newly created menu
            menuItems.add(menu);
            // add the special menus back
            addSpecialMenus((MenuLocationModel) mMenuLocationsSpinner.getSelectedItem(), menuItems);
            // update the spinner items
            mMenusSpinner.setItems(menuItems, BASE_DISPLAY_COUNT_MENUS);
            //set this newly created menu
            mMenusSpinner.setSelection(mMenusSpinner.getItems().size() - 2);
        }
    }

    /*
     * load menus using an AsyncTask
     */
    public void loadMenus() {
        if (mIsLoadTaskRunning) {
            AppLog.w(AppLog.T.MENUS, "load menus task already active");
        } else {
            new LoadMenusTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
    }

    /*
     * AsyncTask to load menus from SQLite
     */
    private boolean mIsLoadTaskRunning = false;


    private class LoadMenusTask extends AsyncTask<Void, Void, Boolean> {
        List<MenuModel> tmpMenus;
        List<MenuLocationModel> tmpMenuLocations;

        @Override
        protected void onPreExecute() {
            mIsLoadTaskRunning = true;
        }

        @Override
        protected void onCancelled() {
            mIsLoadTaskRunning = false;
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            tmpMenus = MenuTable.getAllMenusForCurrentSite();
            tmpMenuLocations = MenuLocationTable.getAllMenuLocationsForCurrentSite();
            return true;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if (result) {
                mMenuLocationsSpinner.setItems(tmpMenuLocations, 0);
                if (tmpMenuLocations != null && tmpMenuLocations.size() > 0) {
                    addSpecialMenus(tmpMenuLocations.get(0), tmpMenus);
                }
                mMenusSpinner.setItems(tmpMenus, BASE_DISPLAY_COUNT_MENUS);
            }

            if ( (!result || tmpMenuLocations == null || tmpMenuLocations.size() == 0)
                    || tmpMenus == null || tmpMenus.size() == 0 ) {
                updateEmptyView(EmptyViewMessageType.NO_CONTENT);
            } else {
                hideEmptyView();
            }

            mIsLoadTaskRunning = false;
        }
    }

    @NonNull private MenuModel getSpecialMenu(long id, String name) {
        MenuModel specialMenu = new MenuModel();
        specialMenu.menuId = id;
        specialMenu.name = name;
        return specialMenu;
    }

    private void insertSpecialMenu(@NonNull List<MenuModel> menus, int pos, MenuModel specialMenu) {
        // make sure not to duplicate menu entry
        for (MenuModel menu : menus) {
            if (menu != null && menu.menuId == specialMenu.menuId) return;
        }
        menus.add(pos, specialMenu);
    }

    private void removeSpecialMenus(List<MenuModel> menus) {
        List<MenuModel> toRemove = new ArrayList<>();
        for (int i = 0; i < menus.size(); ++i) {
            long id = menus.get(i).menuId;
            if (id == MenuModel.ADD_MENU_ID ||
                    id == MenuModel.DEFAULT_MENU_ID ||
                    id == MenuModel.NO_MENU_ID) {
                toRemove.add(menus.get(i));
            }
        }

        for (MenuModel menu : toRemove) {
            menus.remove(menu);
        }
    }

    private void addSpecialMenus(MenuLocationModel location, List<MenuModel> menus) {
        removeSpecialMenus(menus);
        if (MenuLocationModel.LOCATION_DEFAULT.equals(location.defaultState)) {
            MenuModel defaultMenu = getSpecialMenu(MenuModel.DEFAULT_MENU_ID,
                    getString(R.string.menus_default_menu_name));
            insertSpecialMenu(menus, 0, defaultMenu);
        } else if (MenuLocationModel.LOCATION_EMPTY.equals(location.defaultState)) {
            MenuModel noMenu = getSpecialMenu(MenuModel.NO_MENU_ID,
                    getString(R.string.menus_no_menu_name));
            insertSpecialMenu(menus, 0, noMenu);
        }

        MenuModel addMenu = new MenuModel();
        addMenu.menuId = MenuModel.ADD_MENU_ID;
        addMenu.name = getString(R.string.menus_add_menu_name);
        insertSpecialMenu(menus, menus.size(), addMenu);
    }

    private MenuModel setMenuLocation(MenuModel menu) {
        if (menu != null) {
            //first check if this is one of the special menus
            if (!menu.isSpecialMenu()) {

                MenuLocationModel location = (MenuLocationModel) mMenuLocationsSpinner.getSelectedItem();
                if (location != null) {
                    if (menu.locations == null) {
                        menu.locations = new ArrayList<>();
                    }

                    if (menu.locations.size() > 0) {
                        //now add location if not existing there yet
                        for (MenuLocationModel existingLocs : menu.locations) {
                            if (!existingLocs.equals(location)) {
                                menu.locations.add(location);
                                break;
                            }
                        }
                    } else {
                        //no menu locations yet, just add it
                        menu.locations.add(location);
                    }
                }

            } else {
                //if this is a special Menu, we need to retrieve the last "real" menu and erase the current location
                //from its locations list
                if (mCurrentMenuForLocation != null) {
                    mCurrentMenuForLocation.stripLocationFromMenu((MenuLocationModel) mMenuLocationsSpinner.getSelectedItem());
                    return mCurrentMenuForLocation;
                }
            }
        }

        return menu;
    }

    private void saveMenuBackground(@NonNull final MenuModel menu) {
        new AsyncTask<Void, Void, Boolean>() {
            @Override protected Boolean doInBackground(Void... params) {
                MenuTable.saveMenu(menu);
                return null;
            }

            @Override protected void onPostExecute(Boolean result) { }
        }.execute();
    }

    @Override public void onSettingsUpdated(Exception error) {
        if (mDialog != null) {
            mDialog.setTypes(getItemTypes());
        }
    }

    @Override public void onSettingsSaved(Exception error) {
        // no-op
    }

    @Override public void onCredentialsValidated(Exception error) {
        // no-op
    }

    private void showAlertDialog(final int position) {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(
                getActivity());
        dialogBuilder.setTitle(getResources().getText(R.string.menu_item_changes_not_saved_title));
        dialogBuilder.setMessage(getResources().getText(R.string.menu_item_changes_not_saved));
        dialogBuilder.setPositiveButton(getResources().getText(R.string.yes),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        mMenusSpinner.setSelection(position);
                        changeMenuSpinnerSelection(position);
                    }
                });
        dialogBuilder.setNegativeButton(
                getResources().getText(R.string.no),
                null);
        dialogBuilder.setCancelable(true);
        dialogBuilder.create().show();

    }

}
