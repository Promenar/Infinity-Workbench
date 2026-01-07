import { Outlet } from 'react-router-dom';
import { AgentRail } from './AgentRail';

export function MainLayout() {

    // Determine background style based on route? 
    // For now global background is set in index.css

    return (
        <div className="flex h-screen w-screen overflow-hidden text-white relative selection:bg-indigo-500/30">
            {/* Global Background Effects (Beams/Grid) can go here */}

            {/* 1. Agent Rail (Left) */}
            <AgentRail />

            {/* 2. Main Content Area (Includes Context Sidebar + Canvas) */}
            {/* The Outlet will render the specific page which defines its own sidebar if needed */}
            <div className="flex-1 flex flex-col relative z-10 overflow-hidden">
                <Outlet />
            </div>
        </div>
    );
}
