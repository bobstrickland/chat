package dev.rstrickland.chat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.navigation.NavigationView;

import org.json.JSONObject;

import dev.rstrickland.chat.databinding.ActivityMainBinding;
import dev.rstrickland.chat.model.ChatModels;
import dev.rstrickland.chat.model.Profile;
import dev.rstrickland.chat.net.ApiClient;
import dev.rstrickland.chat.net.AvatarLoader;
import dev.rstrickland.chat.net.TokenStore;
import dev.rstrickland.chat.push.Notifications;
import dev.rstrickland.chat.push.PushRegistrar;
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

    /**
     * Session-long listener that acknowledges DELIVERY of every incoming message
     * (from anyone but me) — the Android counterpart to the web's always-on inbox.
     * It drives the sender's ✓✓ even when I'm not viewing that conversation. The
     * open ChatFragment separately posts the stronger `read` receipt.
     */
    private final RealtimeClient.FrameListener deliveryAck = this::ackDelivery;

    /**
     * API 33+ asks before we may show notifications. The outcome doesn't gate
     * REGISTRATION — we register the FCM token either way, so that enabling
     * notifications later in system settings just starts working without needing
     * another sign-in.
     */
    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> PushRegistrar.register(this));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tokens = TokenStore.get(this);

        if (!tokens.isAuthenticated()) {
            goToLogin();
            return;
        }



        OnBackPressedCallback callback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (views != null && views.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    views.drawerLayout.closeDrawer(GravityCompat.START);
                } else {
                    setEnabled(false); // Disable callback to prevent infinite loop
                    getOnBackPressedDispatcher().onBackPressed(); // Fallback to system default
                }
            }
        };

        // 2. Add the callback to the dispatcher
        getOnBackPressedDispatcher().addCallback(this, callback);

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
        bindOwnIdentity(
                header.findViewById(R.id.headerSubtitle),
                header.findViewById(R.id.headerAvatar),
                header.findViewById(R.id.headerAvatarImage));

        // Ensure the realtime socket is up on relaunch, then land on Chat.
        RealtimeClient.get().start(tokens.accessToken());
        RealtimeClient.get().addListener(deliveryAck);

        // Offline push: ask for permission (33+) and register this device's FCM
        // token with the Notification service. Best-effort — see PushRegistrar.
        ensurePushRegistration();

        if (savedInstanceState == null) {
            String fromPush = conversationIdFrom(getIntent());
            showFragment(
                    fromPush != null ? ChatFragment.openingConversation(fromPush) : new ChatFragment(),
                    "Chat", R.id.nav_chat);
        }
    }

    /**
     * A push tapped while the app is already running (singleTop) lands here rather
     * than in onCreate — jump to the conversation it was about.
     */
    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String conversationId = conversationIdFrom(intent);
        if (conversationId != null && views != null) {
            openConversation(conversationId);
        }
    }

    /** The conversation a notification tap refers to, or null for a normal launch. */
    private static String conversationIdFrom(Intent intent) {
        if (intent == null) return null;
        String id = intent.getStringExtra(Notifications.EXTRA_CONVERSATION_ID);
        return (id == null || id.isEmpty()) ? null : id;
    }

    private void ensurePushRegistration() {
        boolean needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED;
        if (needsPermission) {
            // The launcher callback registers once the user has answered.
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            PushRegistrar.register(this);
        }
    }

    @Override
    protected void onDestroy() {
        RealtimeClient.get().removeListener(deliveryAck);
        super.onDestroy();
    }

    /** Post a `delivered` receipt for another user's incoming message (fire-and-forget). */
    private void ackDelivery(JSONObject frame) {
        if (!"message".equals(frame.optString("type"))) return;
        String senderId = frame.optString("senderId");
        if (senderId.isEmpty() || senderId.equals(tokens.userId())) return; // not my own echo
        String conversationId = frame.optString("conversationId");
        String sentAt = frame.optString("sentAt");
        if (conversationId.isEmpty() || sentAt.isEmpty()) return;
        ApiClient.get(this).messaging()
                .sendReceipt(conversationId, new ChatModels.ReceiptRequest("delivered", sentAt))
                .enqueue(new Callback<>() {
                    @Override
                    public void onResponse(@NonNull Call<Void> call, @NonNull Response<Void> res) {
                    }

                    @Override
                    public void onFailure(@NonNull Call<Void> call, @NonNull Throwable t) {
                    }
                });
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
            exit();
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
        // Top-level (drawer) navigation resets any drill-down (e.g. contact detail).
        getSupportFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                .replace(R.id.fragmentHost, fragment)
                .commit();
        setTitle(title);
        views.navView.setCheckedItem(checkedItemId);
        views.drawerLayout.closeDrawer(GravityCompat.START);
    }

    /**
     * Push a drill-down screen (e.g. contact detail) onto the back stack so system
     * back returns to the current destination. The drawer/toolbar stay in place, so
     * the hamburger + nav header are available here exactly like every other screen.
     */
    public void showDetailFragment(Fragment fragment, String title) {
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                .replace(R.id.fragmentHost, fragment)
                .addToBackStack("detail")
                .commit();
        setTitle(title);
        views.drawerLayout.closeDrawer(GravityCompat.START);
    }

    /** Show the signed-in user as display name -> email -> "You". Never the userId. */
    private void bindOwnIdentity(TextView label, TextView avatar, ImageView avatarImage) {
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
                // Show the avatar photo over the initials if the user has one.
                AvatarLoader.load(ApiClient.get(MainActivity.this).media(),
                        p != null ? p.avatarMediaId : null, avatarImage);
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

    private void exit() {
        endSession(this::finishAffinity);
    }

    private void signOut() {
        endSession(this::goToLogin);
    }

    /**
     * Tear down the session: stop the socket, unregister from push, THEN clear the
     * tokens (the unregister call is bearer-authed, so the order matters) and hand
     * off. The callback always runs, so a push-service hiccup can't strand the user
     * in a half-signed-out state.
     */
    private void endSession(Runnable then) {
        RealtimeClient.get().stop();
        PushRegistrar.unregister(this, () -> {
            tokens.clear();
            then.run();
        });
    }

    private void goToLogin() {
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }

}
