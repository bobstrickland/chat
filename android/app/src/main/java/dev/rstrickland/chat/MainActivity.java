package dev.rstrickland.chat;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.navigation.NavigationView;

import dev.rstrickland.chat.databinding.ActivityMainBinding;
import dev.rstrickland.chat.model.Profile;
import dev.rstrickland.chat.net.ApiClient;
import dev.rstrickland.chat.net.TokenStore;
import dev.rstrickland.chat.realtime.RealtimeClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import dev.rstrickland.chat.ui.chat.ChatFragment;
import dev.rstrickland.chat.ui.contacts.ContactsFragment;
import dev.rstrickland.chat.ui.profile.ProfileFragment;
import dev.rstrickland.chat.ui.search.SearchFragment;

/**
 * The signed-in shell: a Material toolbar with the hamburger, and a
 * NavigationView drawer whose items swap the hosted fragment — Chat / Search /
 * Contacts / Profile / Sign Out, mirroring the web nav.
 */
public final class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener {

    private ActivityMainBinding views;
    private TokenStore tokens;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tokens = TokenStore.get(this);

        if (!tokens.isAuthenticated()) {
            goToLogin();
            return;
        }

        views = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(views.getRoot());

        setSupportActionBar(views.toolbar);

        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, views.drawerLayout, views.toolbar, R.string.nav_open, R.string.nav_close);
        views.drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        views.navView.setNavigationItemSelectedListener(this);

        // Nav header: who's signed in — display name, else email, NEVER the userId.
        View header = views.navView.getHeaderView(0);
        bindOwnIdentity(header.findViewById(R.id.headerSubtitle), header.findViewById(R.id.headerAvatar));

        // Ensure the realtime socket is up on relaunch, then land on Chat.
        RealtimeClient.get().start(tokens.accessToken());
        if (savedInstanceState == null) {
            showFragment(new ChatFragment(), "Chat", R.id.nav_chat);
        }
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.nav_chat) {
            showFragment(new ChatFragment(), "Chat", id);
        } else if (id == R.id.nav_search) {
            showFragment(new SearchFragment(), "Search", id);
        } else if (id == R.id.nav_contacts) {
            showFragment(new ContactsFragment(), "Contacts", id);
        } else if (id == R.id.nav_profile) {
            showFragment(new ProfileFragment(), "Profile", id);
        } else if (id == R.id.nav_signout) {
            signOut();
            return true;
        } else if (id == R.id.nav_exit) {
            finishAffinity();
        }
        views.drawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }

    /**
     * Jump to the Chat destination and open {@code conversationId} — the shared
     * entry point Search and Contacts use to start/resume a conversation.
     */
    public void openConversation(String conversationId) {
        showFragment(ChatFragment.openingConversation(conversationId), "Chat", R.id.nav_chat);
    }

    private void showFragment(Fragment fragment, String title, int checkedItemId) {
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                .replace(R.id.fragmentHost, fragment)
                .commit();
        setTitle(title);
        views.navView.setCheckedItem(checkedItemId);
        views.drawerLayout.closeDrawer(GravityCompat.START);
    }

    /** Show the signed-in user as display name -> email -> "You". Never the userId. */
    private void bindOwnIdentity(TextView label, TextView avatar) {
        String email = tokens.email();
        setIdentity(label, avatar, email != null ? email : "You");
        ApiClient.get(this).profile().getMine().enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<Profile> call, @NonNull Response<Profile> res) {
                Profile p = res.body();
                String name = (p != null && p.displayName != null && !p.displayName.trim().isEmpty())
                        ? p.displayName.trim()
                        : (email != null ? email : "You");
                setIdentity(label, avatar, name);
            }

            @Override
            public void onFailure(@NonNull Call<Profile> call, @NonNull Throwable t) {
                /* keep the fallback already set */
            }
        });
    }

    private static void setIdentity(TextView label, TextView avatar, String name) {
        if (label != null) label.setText(name);
        if (avatar != null) avatar.setText(initial(name));
    }

    private static String initial(String s) {
        return (s == null || s.trim().isEmpty()) ? "?" : s.trim().substring(0, 1).toUpperCase();
    }

    private void signOut() {
        RealtimeClient.get().stop();
        tokens.clear();
        goToLogin();
    }

    private void goToLogin() {
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }

    @Override
    public void onBackPressed() {
        if (views != null && views.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            views.drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }
}
