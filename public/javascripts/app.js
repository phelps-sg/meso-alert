// The Auth0 client, initialized in configureClient()
let auth0 = null;

/**
 * Starts the authentication flow
 */
const login = async (withRedirect= false, targetUrl) => {
  try {
    console.log("Logging in", targetUrl);
    const options = {
      redirect_uri: window.location.origin
    };
    if (withRedirect) {
      options.redirect_uri = window.location.origin + "?slack_redirect=1"
    }

    if (targetUrl) {
      options.appState = { targetUrl };
    }

    await auth0.loginWithRedirect(options);

  } catch (err) {
    console.log("Log in failed", err);
  }
};

/**
 * Executes the logout flow
 */
const logout = () => {
  try {
    console.log("Logging out");
    auth0.logout({
      returnTo: window.location.origin
    });
  } catch (err) {
    console.log("Log out failed", err);
  }
};

/**
 * Retrieves the auth configuration from the server
 */
const fetchAuthConfig = () => fetch("/api/auth0/config")

/**
 * Initializes the Auth0 client
 */
const configureClient = async () => {
  const response = await fetchAuthConfig();
  const config = await response.json();

  auth0 = await createAuth0Client({
    domain: config.domain,
    client_id: config.clientId,
    audience: config.audience,
    cacheLocation: 'localstorage'
  });
};

/**
 * Checks to see if the user is authenticated. If so, `fn` is executed. Otherwise, the user
 * is prompted to log in
 * @param {*} fn The function to execute if the user is logged in
 */
const requireAuth = async (fn, targetUrl) => {
  const isAuthenticated = await auth0.isAuthenticated();

  if (isAuthenticated) {
    return fn();
  }

  return login(targetUrl);
};

const getUserId = async () => {
    const user = await auth0.getUser();
    if (user) {
      return user["email"]
    } else {
    return null
  }
}

const callSecretApi = async () => {
  try {
    const userID = await getUserId();
    if (userID) {
      const token = await auth0.getTokenSilently()
      const endpoint = `/api/auth0/secret?userId=${userID}`
      const jsonResponse = await fetch(endpoint, {
        headers: {
          Authorization: `Bearer ${token}`
        }
      }).then(async response => await response.json());

      const secret = jsonResponse.secret
      const slackUrl = jsonResponse.slackUrl

      window.location.href = slackUrl + `&state=(${encodeURIComponent(userID)},${encodeURIComponent(secret)})`
    } else {
      login(true)
    }

  } catch (e) {
    // Display errors in the console
    console.error(e);
  }
};

// Will run when page finishes loading
window.onload = async () => {
  await configureClient();

  const isAuthenticated = await auth0.isAuthenticated();

  if (isAuthenticated) {
    console.log("> User is authenticated");
    window.history.replaceState({}, document.title, window.location.pathname);
    updateUI();
    return;
  }

  console.log("> User not authenticated");

  const query = window.location.search;
  const shouldParseResult = query.includes("code=") && query.includes("state=");
  const shouldRedirect = query.includes("slack_redirect=")
  if (shouldParseResult) {
    console.log("> Parsing redirect");
    try {
      const result = await auth0.handleRedirectCallback();

      if (result.appState && result.appState.targetUrl) {
        showContentFromUrl(result.appState.targetUrl);
      }

      console.log("Logged in!");
    } catch (err) {
      console.log("Error parsing redirect:", err);
    }

    window.history.replaceState({}, document.title, "/");
  }
  if (shouldRedirect) {
    await callSecretApi()
  }

  updateUI();
};
