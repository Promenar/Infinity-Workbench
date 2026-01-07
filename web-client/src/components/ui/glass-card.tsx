import React from 'react';
import { motion, useMotionTemplate, useMotionValue } from 'framer-motion';
import { cn } from '../../lib/utils';

interface GlassCardProps extends React.HTMLAttributes<HTMLDivElement> {
    children: React.ReactNode;
    className?: string;
    enableTilt?: boolean;
    spotlightColor?: string;
}

export const GlassCard = ({
    children,
    className,
    enableTilt = false,
    spotlightColor = "rgba(99, 102, 241, 0.15)", // Indigo default
    ...props
}: GlassCardProps) => {
    const mouseX = useMotionValue(0);
    const mouseY = useMotionValue(0);

    const handleMouseMove = ({ currentTarget, clientX, clientY }: React.MouseEvent) => {
        const { left, top } = currentTarget.getBoundingClientRect();
        mouseX.set(clientX - left);
        mouseY.set(clientY - top);
    };

    return (
        <motion.div
            className={cn(
                "group relative border border-white/10 bg-black/40 backdrop-blur-xl overflow-hidden rounded-2xl",
                "hover:border-white/20 transition-colors duration-300",
                className
            )}
            onMouseMove={handleMouseMove}
            whileHover={enableTilt ? { scale: 1.02 } : {}}
            transition={{ type: "spring", stiffness: 300, damping: 20 }}
            {...(props as any)}
        >
            <motion.div
                className="pointer-events-none absolute -inset-px opacity-0 group-hover:opacity-100 transition duration-300"
                style={{
                    background: useMotionTemplate`
                        radial-gradient(
                            650px circle at ${mouseX}px ${mouseY}px,
                            ${spotlightColor},
                            transparent 80%
                        )
                    `,
                }}
            />
            <div className="relative h-full">
                {children}
            </div>
        </motion.div>
    );
};
