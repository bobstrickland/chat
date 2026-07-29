package dev.rstrickland.chat.ui.contacts;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import dev.rstrickland.chat.MainActivity;
import dev.rstrickland.chat.R;
import dev.rstrickland.chat.databinding.FragmentContactsBinding;
import dev.rstrickland.chat.model.ContactModels;
import dev.rstrickland.chat.model.ContactModels.Contact;
import dev.rstrickland.chat.net.ApiClient;
import dev.rstrickland.chat.net.ConversationIds;
import dev.rstrickland.chat.net.TokenStore;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * The signed-in user's contacts (Phase 11). Lists the contacts, lets you start a
 * direct chat with one, or remove one. Mirrors the web contacts page.
 */
public final class ContactsFragment extends Fragment implements ContactAdapter.Actions {

    private FragmentContactsBinding views;
    private ApiClient api;
    private String myUserId;
    private ContactAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        views = FragmentContactsBinding.inflate(inflater, container, false);
        return views.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        api = ApiClient.get(requireContext());
        myUserId = TokenStore.get(requireContext()).userId();

        adapter = new ContactAdapter(this, api.media());
        views.rvContacts.setLayoutManager(new LinearLayoutManager(requireContext()));
        views.rvContacts.setLayoutAnimation(
                AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_animation));
        views.rvContacts.setAdapter(adapter);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Restore the title (it was changed by the detail screen) and reload — the
        // latter also refreshes after returning from detail (e.g. a removed contact).
        requireActivity().setTitle("Contacts");
        if (views != null) load();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        views = null;
    }

    private void load() {
        showStatus("Loading…");
        api.contacts().list().enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<ContactModels.ContactsResponse> call,
                                   @NonNull Response<ContactModels.ContactsResponse> res) {
                if (views == null) return;
                if (res.body() != null && res.body().contacts != null && !res.body().contacts.isEmpty()) {
                    adapter.submit(res.body().contacts);
                    views.rvContacts.scheduleLayoutAnimation();
                    hideStatus();
                } else if (res.body() != null) {
                    adapter.submit(res.body().contacts);
                    showStatus("No contacts yet.\nAdd people from Search.");
                } else {
                    showStatus("Could not load contacts.");
                }
            }

            @Override
            public void onFailure(@NonNull Call<ContactModels.ContactsResponse> call, @NonNull Throwable t) {
                if (views != null) showStatus("Network error: " + t.getMessage());
            }
        });
    }

    // ---- row actions ----

    @Override
    public void onMessage(Contact contact) {
        if (contact.userId == null || myUserId == null) return;
        String conversationId = ConversationIds.direct(myUserId, contact.userId);
        ((MainActivity) requireActivity()).openConversation(conversationId);
    }

    @Override
    public void onOpen(Contact contact) {
        if (contact.userId == null) return;
        ((MainActivity) requireActivity())
                .showDetailFragment(ContactDetailFragment.of(contact), contact.label());
    }

    @Override
    public void onRemove(Contact contact) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Remove contact")
                .setMessage("Remove " + contact.label() + " from your contacts?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> doRemove(contact))
                .show();
    }

    private void doRemove(Contact contact) {
        api.contacts().remove(contact.userId).enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<Void> call, @NonNull Response<Void> res) {
                if (views == null) return;
                if (res.isSuccessful()) {
                    load();
                } else {
                    toast("Could not remove contact.");
                }
            }

            @Override
            public void onFailure(@NonNull Call<Void> call, @NonNull Throwable t) {
                toast("Network error removing contact.");
            }
        });
    }

    // ---- status helpers ----

    private void showStatus(String text) {
        if (views == null) return;
        views.status.setText(text);
        views.status.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        if (views != null) views.status.setVisibility(View.GONE);
    }

    private void toast(String text) {
        if (isAdded()) Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show();
    }
}
