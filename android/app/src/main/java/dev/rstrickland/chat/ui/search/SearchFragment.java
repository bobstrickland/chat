package dev.rstrickland.chat.ui.search;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.util.HashSet;
import java.util.Set;

import dev.rstrickland.chat.MainActivity;
import dev.rstrickland.chat.databinding.FragmentSearchBinding;
import dev.rstrickland.chat.model.ContactModels;
import dev.rstrickland.chat.model.SearchModels;
import dev.rstrickland.chat.model.SearchModels.MessageHit;
import dev.rstrickland.chat.model.SearchModels.UserHit;
import dev.rstrickland.chat.net.ApiClient;
import dev.rstrickland.chat.net.ConversationIds;
import dev.rstrickland.chat.net.NameResolver;
import dev.rstrickland.chat.net.TokenStore;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Global search over messages (the caller's own conversations, scoped server-side)
 * and people (public directory). Debounced — it fires as you type. A person hit
 * starts a direct chat or can be added as a contact; a message hit opens its
 * conversation. Mirrors the web search page.
 */
public final class SearchFragment extends Fragment implements SearchResultsAdapter.Actions {

    private static final long DEBOUNCE_MS = 300L;

    private FragmentSearchBinding views;
    private ApiClient api;
    private String myUserId;
    private SearchResultsAdapter adapter;

    private final Handler debounce = new Handler(Looper.getMainLooper());
    private Runnable pending;
    private String currentQuery = "";

    /** Which users are already contacts — drives the "+ Add" vs "✓ contact" state. */
    private final Set<String> contactIds = new HashSet<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        views = FragmentSearchBinding.inflate(inflater, container, false);
        return views.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        api = ApiClient.get(requireContext());
        myUserId = TokenStore.get(requireContext()).userId();

        adapter = new SearchResultsAdapter(this, NameResolver.get(requireContext()));
        views.rvResults.setLayoutManager(new LinearLayoutManager(requireContext()));
        views.rvResults.setAdapter(adapter);

        views.searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                scheduleSearch(s.toString().trim());
            }
        });

        loadContacts();
        showStatus("Search messages and people.");
    }

    @Override
    public void onDestroyView() {
        if (pending != null) debounce.removeCallbacks(pending);
        super.onDestroyView();
        views = null;
    }

    // ---- contacts (for add/added state) ----

    private void loadContacts() {
        api.contacts().list().enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<ContactModels.ContactsResponse> call,
                                   @NonNull Response<ContactModels.ContactsResponse> res) {
                if (views == null || res.body() == null || res.body().contacts == null) return;
                contactIds.clear();
                for (ContactModels.Contact c : res.body().contacts) {
                    if (c.userId != null) contactIds.add(c.userId);
                }
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onFailure(@NonNull Call<ContactModels.ContactsResponse> call, @NonNull Throwable t) {
                /* add still works; the row just shows "+ Add" */
            }
        });
    }

    // ---- search ----

    private void scheduleSearch(String query) {
        if (pending != null) debounce.removeCallbacks(pending);
        currentQuery = query;
        if (query.isEmpty()) {
            adapter.clear();
            showStatus("Search messages and people.");
            return;
        }
        pending = () -> runSearch(query);
        debounce.postDelayed(pending, DEBOUNCE_MS);
    }

    private void runSearch(String query) {
        showStatus("Searching…");
        api.search().search(query, "all").enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<SearchModels.SearchResults> call,
                                   @NonNull Response<SearchModels.SearchResults> res) {
                if (views == null || !query.equals(currentQuery)) return; // stale response
                if (res.isSuccessful() && res.body() != null) {
                    adapter.submit(res.body());
                    if (adapter.isEmpty()) showStatus("No matches for “" + query + "”.");
                    else hideStatus();
                } else {
                    adapter.clear();
                    showStatus("Search failed.");
                }
            }

            @Override
            public void onFailure(@NonNull Call<SearchModels.SearchResults> call, @NonNull Throwable t) {
                if (views == null || !query.equals(currentQuery)) return;
                adapter.clear();
                showStatus("Network error: " + t.getMessage());
            }
        });
    }

    // ---- result actions ----

    @Override
    public boolean isContact(String userId) {
        return contactIds.contains(userId);
    }

    @Override
    public void onAddContact(UserHit user) {
        if (user.userId == null) return;
        api.contacts().add(new ContactModels.AddRequest(user.userId)).enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<ContactModels.Contact> call,
                                   @NonNull Response<ContactModels.Contact> res) {
                if (views == null) return;
                if (res.isSuccessful()) {
                    contactIds.add(user.userId);
                    adapter.notifyDataSetChanged();
                    toast("Added " + user.label() + " to contacts.");
                } else {
                    toast("Could not add contact.");
                }
            }

            @Override
            public void onFailure(@NonNull Call<ContactModels.Contact> call, @NonNull Throwable t) {
                toast("Network error adding contact.");
            }
        });
    }

    @Override
    public void onMessageUser(UserHit user) {
        if (user.userId == null || myUserId == null) return;
        ((MainActivity) requireActivity())
                .openConversation(ConversationIds.direct(myUserId, user.userId));
    }

    @Override
    public void onOpenMessage(MessageHit message) {
        if (message.conversationId == null) return;
        ((MainActivity) requireActivity()).openConversation(message.conversationId);
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
