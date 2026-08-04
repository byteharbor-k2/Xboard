import { useEffect, useState } from "react";

export type PortalAnnouncement = {
  id: string;
  title: string;
  summary: string;
  publishedAt: string;
};

type AnnouncementCarouselProps = {
  announcements: PortalAnnouncement[];
  label: string;
  nextLabel: string;
  previousLabel: string;
};

export function AnnouncementCarousel({
  announcements,
  label,
  nextLabel,
  previousLabel
}: AnnouncementCarouselProps) {
  const [activeIndex, setActiveIndex] = useState(0);

  useEffect(() => {
    if (announcements.length < 2) {
      return;
    }
    const timer = window.setTimeout(() => {
      setActiveIndex((current) => (current + 1) % announcements.length);
    }, 2000);
    return () => window.clearTimeout(timer);
  }, [activeIndex, announcements.length]);

  if (announcements.length === 0) {
    return null;
  }

  const activeAnnouncement =
    announcements[activeIndex % announcements.length];

  function showPrevious() {
    setActiveIndex(
      (current) =>
        (current - 1 + announcements.length) % announcements.length
    );
  }

  function showNext() {
    setActiveIndex((current) => (current + 1) % announcements.length);
  }

  return (
    <section
      aria-label={label}
      className="portal-announcement-carousel"
      data-tone={(activeIndex % 3) + 1}
    >
      <div className="portal-announcement-art" aria-hidden="true">
        <span />
        <span />
        <strong>SinX</strong>
      </div>
      <div className="portal-announcement-content">
        <span>{label}</span>
        <h2>{activeAnnouncement.title}</h2>
        <p>{activeAnnouncement.summary}</p>
        <time>{activeAnnouncement.publishedAt}</time>
      </div>
      {announcements.length > 1 && (
        <div className="portal-announcement-controls">
          <button
            aria-label={previousLabel}
            onClick={showPrevious}
            type="button"
          >
            ←
          </button>
          <strong>
            {activeIndex + 1}
            <span>/</span>
            {announcements.length}
          </strong>
          <button
            aria-label={nextLabel}
            onClick={showNext}
            type="button"
          >
            →
          </button>
        </div>
      )}
    </section>
  );
}
