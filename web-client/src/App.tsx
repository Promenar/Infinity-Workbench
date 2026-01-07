import { createBrowserRouter, RouterProvider, Navigate } from 'react-router-dom';
import { useState, useEffect } from 'react';
import AuthScreen from './components/AuthScreen';
import { DashboardLayout } from './components/DashboardLayout';
import { Assistants } from './pages/Assistants';
import { Sessions } from './pages/Sessions';
import { Settings } from './pages/Settings';
import { Library } from './pages/Library';
import { ChatPage } from './pages/ChatPage';
import { KnowledgeGraph } from './pages/KnowledgeGraph';

import { workbenchClient } from './services/WorkbenchClient';
import { storeService } from './services/StoreService';

// Wrapper to handle Auth state
function AuthGuard({ children }: { children: React.ReactNode }) {
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [isChecking, setIsChecking] = useState(true);

  useEffect(() => {
    let timeoutId: any;

    const handleStatus = (status: string) => {
      // Clear any pending disconnect logic if we regain connection/auth
      if (status === 'authenticated' || status === 'connecting' || status === 'connected') {
        if (timeoutId) {
          console.log('[AuthGuard] Connection restored, clearing disconnect timer');
          clearTimeout(timeoutId);
          timeoutId = null;
        }
      }

      if (status === 'authenticated') {
        setIsAuthenticated(true);
        setIsChecking(false);
        storeService.init().catch(console.error);
      } else if (status === 'connected') {
        // Do nothing, wait for auth challenge
      } else if (status === 'disconnected' || status === 'error') {
        // Debounce disconnect to avoid flickering during refresh/deep-link
        if (isAuthenticated) {
          console.log('[AuthGuard] Disconnected, starting grace period...');
          timeoutId = setTimeout(() => {
            console.log('[AuthGuard] Grace period expired, logging out');
            setIsAuthenticated(false);
            setIsChecking(false);
          }, 2500); // 2.5s grace period for reloads/reconnects
        } else {
          setIsAuthenticated(false);
          setIsChecking(false);
        }
      }
    };

    const handleAuthFail = () => {
      console.log('[AuthGuard] Explicit AUTH_FAIL received');
      if (timeoutId) clearTimeout(timeoutId);
      setIsAuthenticated(false);
      setIsChecking(false);
    };

    workbenchClient.on('statusChange', handleStatus);
    workbenchClient.on('auth_fail', handleAuthFail);

    // Initial Check
    const token = localStorage.getItem('wb_token');
    const savedStatus = workbenchClient.getStatus();

    // Logic: If token exists, we are optimistic, but if explicit disconnected/idle, we connect
    if (token) {
      if (savedStatus === 'authenticated') {
        setIsAuthenticated(true);
        setIsChecking(false);
      } else {
        // Attempt connect if not already
        const origin = window.location.origin;
        if (savedStatus === 'disconnected' && origin.includes('http')) {
          workbenchClient.connect(origin, '');
        }
        // If status is 'connecting' or 'connected', just wait. 
        // But if it takes too long? We rely on UI spinner.
      }
    } else {
      setIsChecking(false);
    }

    return () => {
      workbenchClient.off('statusChange', handleStatus);
      workbenchClient.off('auth_fail', handleAuthFail);
      if (timeoutId) clearTimeout(timeoutId);
    };
  }, [isAuthenticated]);

  if (isChecking) {
    return (
      <div className="flex items-center justify-center h-screen bg-black text-white">
        <div className="text-center">
          <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-blue-500 mx-auto mb-4"></div>
          <p className="text-zinc-400">Connecting to Workbench...</p>
        </div>
      </div>
    );
  }

  if (!isAuthenticated) {
    return <AuthScreen onLogin={() => setIsAuthenticated(true)} />;
  }

  return <>{children}</>;
}

const router = createBrowserRouter([
  {
    path: "/",
    element: (
      <AuthGuard>
        <DashboardLayout />
      </AuthGuard>
    ),
    children: [
      { index: true, element: <Navigate to="/sessions" replace /> },
      { path: "assistants", element: <Assistants /> },
      { path: "sessions", element: <Sessions /> },
      { path: "chat/:sessionId", element: <ChatPage /> },
      { path: "library", element: <Library /> },
      { path: "graph", element: <KnowledgeGraph /> },
      { path: "settings", element: <Settings /> },
      // Dashboard route removed or redirected
      { path: "dashboard", element: <Navigate to="/sessions" replace /> },
    ]
  },
  {
    path: "*",
    element: <Navigate to="/" replace />
  }
]);

function App() {
  return <RouterProvider router={router} />;
}

export default App;
