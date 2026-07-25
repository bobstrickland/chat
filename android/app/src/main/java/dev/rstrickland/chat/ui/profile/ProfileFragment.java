package dev.rstrickland.chat.ui.profile;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import dev.rstrickland.chat.R;
import dev.rstrickland.chat.databinding.FragmentProfileBinding;
import dev.rstrickland.chat.model.Profile;
import dev.rstrickland.chat.net.ApiClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** View + edit the signed-in user's own profile (Phase 2/10 fields). */
public final class ProfileFragment extends Fragment {

    private static final String[] VISIBILITIES = {"PUBLIC", "CONTACTS", "PRIVATE"};

    private FragmentProfileBinding views;
    private ApiClient api;
    private Profile current;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        views = FragmentProfileBinding.inflate(inflater, container, false);
        return views.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        api = ApiClient.get(requireContext());

        views.visibility.setAdapter(new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_list_item_1, VISIBILITIES));

        views.saveButton.setOnClickListener(v -> save());
        view.startAnimation(AnimationUtils.loadAnimation(requireContext(), R.anim.fade_in));
        load();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        views = null;
    }

    private void load() {
        views.status.setText("Loading…");
        api.profile().getMine().enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<Profile> call, @NonNull Response<Profile> res) {
                if (views == null) return;
                if (res.body() != null) {
                    current = res.body();
                    bind(current);
                    views.status.setText("");
                } else {
                    views.status.setText("Could not load profile.");
                }
            }

            @Override
            public void onFailure(@NonNull Call<Profile> call, @NonNull Throwable t) {
                if (views != null) views.status.setText("Network error: " + t.getMessage());
            }
        });
    }

    private void bind(Profile p) {
        views.displayName.setText(p.displayName != null ? p.displayName : "");
        views.phone.setText(p.phone != null ? p.phone : "");
        views.bio.setText(p.bio != null ? p.bio : "");
        views.visibility.setText(p.visibility != null ? p.visibility : "PUBLIC", false);
        views.profileAvatar.setText(initials(p.displayName));
    }

    private static String initials(String name) {
        if (name == null || name.trim().isEmpty()) return "?";
        return name.trim().substring(0, 1).toUpperCase();
    }

    private void save() {
        if (current == null) return;
        String display = views.displayName.getText().toString().trim();
        if (display.isEmpty()) {
            views.status.setText("Display name is required.");
            return;
        }
        String phone = views.phone.getText().toString().trim();
        String bio = views.bio.getText().toString().trim();
        String visibility = views.visibility.getText().toString().trim();
        if (visibility.isEmpty()) visibility = "PUBLIC";

        Profile.Update update = new Profile.Update(
                display,
                bio.isEmpty() ? null : bio,
                phone.isEmpty() ? null : phone,
                visibility);

        views.status.setText("Saving…");
        api.profile().update(current.userId, update).enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<Profile> call, @NonNull Response<Profile> res) {
                if (views == null) return;
                if (res.isSuccessful() && res.body() != null) {
                    current = res.body();
                    views.status.setText("Saved.");
                } else {
                    views.status.setText("Save failed.");
                }
            }

            @Override
            public void onFailure(@NonNull Call<Profile> call, @NonNull Throwable t) {
                if (views != null) views.status.setText("Network error: " + t.getMessage());
            }
        });
    }

}
