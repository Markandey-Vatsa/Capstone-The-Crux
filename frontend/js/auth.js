// Authentication and JWT management functionality

// Ensure API base URL is set; provide a sensible fallback if missing
if(!window.API_BASE_URL){ window.API_BASE_URL = 'http://localhost:8080/api'; }
const API_BASE_URL = window.API_BASE_URL;

// Unified safe fetch wrapper with offline detection & standardized errors
async function safeFetch(url, options){
    try {
        const resp = await fetch(url, options);
        return resp;
    } catch(err){
        if(!navigator.onLine){
            uiUtils.showToast('You appear to be offline. Please check your connection.', 'error');
        } else {
            uiUtils.showToast('Network error reaching server.', 'error');
        }
        throw err;
    }
}

// DOM elements and event listeners setup
document.addEventListener('DOMContentLoaded', function() {
    // Handle registration form submission
    const registerForm = document.getElementById('register-form');
    if (registerForm) {
        registerForm.addEventListener('submit', handleRegistration);
    }
    
    // Handle login form submission
    const loginForm = document.getElementById('login-form');
    if (loginForm) {
        loginForm.addEventListener('submit', handleLogin);
    }
});

// Handle user registration
async function handleRegistration(event) {
    event.preventDefault();
    
    // Get form data
    const formData = new FormData(event.target);
    const registrationData = {
        username: formData.get('username'),
        email: formData.get('email'),
        password: formData.get('password')
    };
    
    try {
        // Disable form during submission
        const submitButton = event.target.querySelector('button[type="submit"]');
        submitButton.disabled = true;
        submitButton.textContent = 'Creating Account...';
        
        console.log('Attempting registration to:', `${API_BASE_URL}/auth/register`);
        console.log('Registration data:', registrationData);
        
        // Make API call to register endpoint
    const response = await safeFetch(`${API_BASE_URL}/auth/register`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(registrationData)
        });
        
        console.log('Registration response status:', response.status);
        
        if (!response.ok) {
            console.error('Registration failed with status:', response.status);
            if (response.status === 404) {
                uiUtils.showToast('Backend server not reachable. Please check if the server is running on port 8080.', 'error');
                return;
            }
        }
        
        const result = await response.json();
        console.log('Registration result:', result);
        
        if (response.ok) {
            // Registration successful
            uiUtils.showToast('Registration successful! Please log in with your credentials.', 'success');
            setTimeout(() => {
                window.location.href = 'login.html';
            }, 2000);
        } else {
            // Registration failed
            uiUtils.showToast(result.message || 'Registration failed. Please try again.', 'error');
        }
        
    } catch (error) {
        console.error('Registration error:', error);
    uiUtils.showToast('Network error. Please check if the backend server is running and try again.', 'error');
    } finally {
        // Re-enable form
        const submitButton = event.target.querySelector('button[type="submit"]');
        submitButton.disabled = false;
        submitButton.textContent = 'Create Account';
    }
}

// Handle user login
async function handleLogin(event) {
    event.preventDefault();
    
    // Get form data
    const formData = new FormData(event.target);
    const loginData = {
        username: formData.get('username'),
        password: formData.get('password')
    };
    
    try {
        // Disable form during submission
        const submitButton = event.target.querySelector('button[type="submit"]');
        submitButton.disabled = true;
        submitButton.textContent = 'Signing In...';
        
        console.log('Attempting login to:', `${API_BASE_URL}/auth/login`);
        console.log('Login data:', { username: loginData.username, password: '[HIDDEN]' });
        
        // Make API call to login endpoint
    const response = await safeFetch(`${API_BASE_URL}/auth/login`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(loginData)
        });
        
        console.log('Login response status:', response.status);
        
        if (!response.ok) {
            console.error('Login failed with status:', response.status);
            if (response.status === 404) {
                uiUtils.showToast('Backend server not reachable. Please check if the server is running on port 8080.', 'error');
                return;
            }
        }
        
        const result = await response.json();
        console.log('Login result:', result);
        
        if (response.ok && result.token) {
            // Login successful - save JWT token and redirect
            // Store JWT under unified key expected by protected route fetch logic
            localStorage.setItem('jwt_token', result.token);
            // Keep legacy key for backward compatibility
            localStorage.setItem('authToken', result.token);
            localStorage.setItem('username', result.username);
            
            uiUtils.showToast('Login successful! Redirecting...', 'success');
            setTimeout(() => {
                // Check if user was trying to access a specific page before login
                const intendedPage = localStorage.getItem('intendedPage');
                if (intendedPage) {
                    localStorage.removeItem('intendedPage');
                    window.location.href = intendedPage;
                } else {
                    window.location.href = 'index.html';
                }
            }, 1500);
        } else {
            // Login failed
            uiUtils.showToast(result.message || 'Invalid credentials. Please try again.', 'error');
        }
        
    } catch (error) {
        console.error('Login error:', error);
    uiUtils.showToast('Network error. Please check if the backend server is running and try again.', 'error');
    } finally {
        // Re-enable form
        const submitButton = event.target.querySelector('button[type="submit"]');
        submitButton.disabled = false;
        submitButton.textContent = 'Sign In';
    }
}

// Logout function to clear stored data and redirect
function logout() {
    console.log('Auth.js - Logging out user');
    localStorage.removeItem('authToken');
    localStorage.removeItem('jwt_token');
    localStorage.removeItem('username');
    localStorage.removeItem('intendedPage');
    
    uiUtils.showToast('You have been logged out successfully.', 'info');
    
    console.log('Auth.js - Redirecting to login');
    // Immediate redirect without delay to prevent white screen
    setTimeout(() => {
        window.location.href = 'login.html';
    }, 500); // Reduced delay
}

// Check if user is authenticated
function isAuthenticated() {
    const token = localStorage.getItem('jwt_token') || localStorage.getItem('authToken');
    return token !== null && token !== undefined;
}

// Redirect to login if user is not authenticated
function requireAuth() {
    if (!isAuthenticated()) {
        console.log('User not authenticated, redirecting to login');
        // Store the page the user was trying to access
        localStorage.setItem('intendedPage', window.location.href);
        window.location.href = 'login.html';
        return false;
    }
    return true;
}

// Get stored authentication token
function getAuthToken() {
    return localStorage.getItem('jwt_token') || localStorage.getItem('authToken');
}

// Get stored username
function getUsername() {
    return localStorage.getItem('username');
}

// Make authenticated API requests with JWT token
async function makeAuthenticatedRequest(url, options = {}) {
    const token = getAuthToken();
    
    if (!token) {
        throw new Error('No authentication token found');
    }
    
    // Add authorization header to request
    const authOptions = {
        ...options,
        headers: {
            'Content-Type': 'application/json',
            'Authorization': 'Bearer ' + localStorage.getItem('jwt_token'),
            ...(options.headers || {})
        }
    };
    
    const response = await safeFetch(url, authOptions);
    
    // If unauthorized, redirect to login
    if (response.status === 401) {
        logout();
        return;
    }
    
    return response;
}

// Export functions for use in other scripts
window.authUtils = {
    logout,
    isAuthenticated,
    requireAuth,
    getAuthToken,
    getUsername,
    makeAuthenticatedRequest,
    safeFetch
};
