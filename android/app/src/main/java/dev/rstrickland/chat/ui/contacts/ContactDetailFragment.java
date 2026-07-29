package dev.rstrickland.chat.ui.contacts;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import dev.rstrickland.chat.MainActivity;
import dev.rstrickland.chat.databinding.FragmentContactDetailBinding;
import dev.rstrickland.chat.model.ContactModels.Contact;
import dev.rstrickland.chat.model.Profile;
import dev.rstrickland.chat.net.ApiClient;
import dev.rstrickland.chat.net.AvatarLoader;
import dev.rstrickland.chat.net.ConversationIds;
import dev.rstrickland.chat.net.TokenStore;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * A contact's detail screen: their avatar (photo if available, else initials), a
 * Message button right below it, whatever profile info is viewable, and a Remove
 * button at the bottom. Hosted in MainActivity's fragment container like every
 * other logged-in screen, so the drawer (hamburger + nav header) is present here too.
 *
 * Reached by tapping a contact row (anywhere but its buttons). Full profile fields
 * (bio/phone/links/tags) are fetched via GET /profiles/{id}, which honours Phase 11
 * visibility — a restricted profile returns only basic identity, and we show a
 * "not viewable" note instead of empty fields.
 */
public final class ContactDetailFragment extends Fragment {

    private static final String ARG_USER_ID = "userId";
    private static final String ARG_DISPLAY_NAME = "displayName";
    private static final String ARG_AVATAR = "avatarMediaId";
    private static final String ARG_ADDED_AT = "addedAt";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

    /** Build a detail fragment for a contact row. */
    public static ContactDetailFragment of(Contact c) {
        ContactDetailFragment f = new ContactDetailFragment();
        Bundle args = new Bundle();
        args.putString(ARG_USER_ID, c.userId);
        args.putString(ARG_DISPLAY_NAME, c.displayName);
        args.putString(ARG_AVATAR, c.avatarMediaId);
        args.putString(ARG_ADDED_AT, c.addedAt);
        f.setArguments(args);
        return f;
    }

    private FragmentContactDetailBinding views;
    private ApiClient api;
    private String userId;
    private String displayName;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        views = FragmentContactDetailBinding.inflate(inflater, container, false);
        return views.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        api = ApiClient.get(requireContext());

        Bundle args = getArguments();
        userId = args != null ? args.getString(ARG_USER_ID) : null;
        displayName = args != null ? args.getString(ARG_DISPLAY_NAME) : null;
        String avatarMediaId = args != null ? args.getString(ARG_AVATAR) : null;
        String addedAt = args != null ? args.getString(ARG_ADDED_AT) : null;

        if (userId == null) {
            getParentFragmentManager().popBackStack();
            return;
        }

        String label = label(displayName);
        requireActivity().setTitle(label);
        views.detailName.setText(label);
        views.detailAvatar.setText(initial(label));
        AvatarLoader.load(api.media(), avatarMediaId, views.detailAvatarImage);

        showBlock(views.blockAdded, views.valAdded, formatDate(addedAt));

        views.detailMessageButton.setOnClickListener(v -> message());
        views.detailRemoveButton.setOnClickListener(v -> confirmRemove());

        loadProfile();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        views = null;
    }

    /** Fetch fuller profile info; may come back restricted (basic identity only). */
    private void loadProfile() {
        api.profile().get(userId).enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<Profile> call, @NonNull Response<Profile> res) {
                if (views == null) return;
                Profile p = res.body();
                if (p == null) return;

                // Keep the freshest identity (in case the row was stale).
                if (p.displayName != null && !p.displayName.trim().isEmpty()) {
                    displayName = p.displayName.trim();
                    views.detailName.setText(displayName);
                    views.detailAvatar.setText(initial(displayName));
                    requireActivity().setTitle(displayName);
                }
                if (p.avatarMediaId != null) {
                    AvatarLoader.load(api.media(), p.avatarMediaId, views.detailAvatarImage);
                }

                if (Boolean.TRUE.equals(p.restricted)) {
                    views.detailRestricted.setText(
                            "This profile is private — only their name and photo are shown.");
                    views.detailRestricted.setVisibility(View.VISIBLE);
                }

                showBlock(views.blockBio, views.valBio, p.bio);
                showBlock(views.blockPhone, views.valPhone, p.phone);
                showLinks(p.links);
                showBlock(views.blockTags, views.valTags, joinComma(p.tags));
                showBlock(views.blockVisibility, views.valVisibility, p.visibility);
            }

            @Override
            public void onFailure(@NonNull Call<Profile> call, @NonNull Throwable t) {
                /* keep whatever we have from the args */
            }
        });
    }

    private void message() {
        String me = TokenStore.get(requireContext()).userId();
        if (me == null) return;
        // openConversation clears the drill-down back stack and opens the chat.
        ((MainActivity) requireActivity()).openConversation(ConversationIds.direct(me, userId));
    }

    private void confirmRemove() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Remove contact")
                .setMessage("Remove " + label(displayName) + " from your contacts?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> remove())
                .show();
    }

    private void remove() {
        views.detailRemoveButton.setEnabled(false);
        api.contacts().remove(userId).enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<Void> call, @NonNull Response<Void> res) {
                if (!isAdded()) return;
                if (res.isSuccessful()) {
                    // Back to the list (ContactsFragment reloads in onResume).
                    getParentFragmentManager().popBackStack();
                } else if (views != null) {
                    views.detailRemoveButton.setEnabled(true);
                }
            }

            @Override
            public void onFailure(@NonNull Call<Void> call, @NonNull Throwable t) {
                if (views != null) views.detailRemoveButton.setEnabled(true);
            }
        });
    }

    // ---- helpers ----

    private static void showBlock(View block, TextView value, String text) {
        if (text == null || text.trim().isEmpty()) {
            block.setVisibility(View.GONE);
        } else {
            value.setText(text.trim());
            block.setVisibility(View.VISIBLE);
        }
    }

    private static String label(String name) {
        return (name != null && !name.trim().isEmpty()) ? name.trim() : "Unknown";
    }

    private static String initial(String name) {
        String l = label(name);
        return l.equals("Unknown") ? "?" : l.substring(0, 1).toUpperCase();
    }

    /**
     * Render each link as a tappable span. Tapping asks for confirmation before
     * leaving the app for a browser (rather than auto-opening via autoLink).
     */
    private void showLinks(List<String> links) {
        if (links == null || links.isEmpty()) {
            views.blockLinks.setVisibility(View.GONE);
            return;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (String raw : links) {
            final String url = raw == null ? "" : raw.trim();
            if (url.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n");
            int start = sb.length();
            sb.append(url);
            sb.setSpan(new ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    confirmOpenUrl(url);
                }
            }, start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (sb.length() == 0) {
            views.blockLinks.setVisibility(View.GONE);
            return;
        }
        views.valLinks.setText(sb);
        views.valLinks.setMovementMethod(LinkMovementMethod.getInstance());
        views.blockLinks.setVisibility(View.VISIBLE);
    }

    /** Confirm before launching a browser for an external link. */
    private void confirmOpenUrl(String url) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Open link?")
                .setMessage(url)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open", (d, w) -> openUrl(url))
                .show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            if (isAdded()) {
                Toast.makeText(requireContext(), "Couldn't open the link.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static String joinComma(List<String> items) {
        return (items == null || items.isEmpty()) ? null : String.join(", ", items);
    }

    private static String formatDate(String iso) {
        if (iso == null || iso.isEmpty()) return null;
        try {
            return DATE.format(Instant.parse(iso).atZone(ZoneId.systemDefault()));
        } catch (Exception e) {
            return null;
        }
    }
}
