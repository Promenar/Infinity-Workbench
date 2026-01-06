import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { useState, useEffect } from 'react';
import AuthScreen from './components/AuthScreen';
import { DashboardLayout } from './components/DashboardLayout';
import { Dashboard, Library } from './pages/placeholders';
import { Assistants } from './pages/Assistants';
import { Sessions } from './pages/Sessions';
import { Settings } from './pages/Settings';

function App() {
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [isAuthChecking, setIsAuthChecking] = useState(true);

  // This is a placeholder for actual authentication logic.
  // For demonstration, we'll simulate authentication.
  // In a real app, you'd check localStorage, make an API call, etc.
  // In a real app, you'd check localStorage, make an API call, etc.
  useEffect(() => {
    // Simulate auth check
    setTimeout(() => {
      const userToken = localStorage.getItem('userToken');
      if (userToken) {
        setIsAuthenticated(true);
      }
      setIsAuthChecking(false);
    }, 500);
  }, []);

  const handleLogin = () => {
    localStorage.setItem('userToken', 'some-token'); // Simulate successful login
    setIsAuthenticated(true);
  };

  if (isAuthChecking) {
    return <div>Loading authentication...</div>; // Or a spinner
  }

  if (!isAuthenticated) {
    return <AuthScreen onLogin={handleLogin} />;
  }

  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<DashboardLayout />}>
          <Route index element={<Navigate to="/sessions" replace />} /> {/* Default route after login */}
          <Route path="dashboard" element={<Dashboard />} /> {/* Kept for now, but could be removed based on snippet */}
          <Route path="assistants" element={<Assistants />} />
          <Route path="sessions" element={<Sessions />} />
          <Route path="library" element={<Library />} /> {/* Kept for now, but could be removed based on snippet */}
          <Route path="settings" element={<Settings />} /> {/* New settings route */}
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
