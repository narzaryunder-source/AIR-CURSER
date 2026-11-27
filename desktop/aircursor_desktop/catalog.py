"""Verified Windows Package Manager IDs and official game listings."""

from dataclasses import dataclass


@dataclass(frozen=True)
class GameListing:
    name: str
    genre: str
    launcher: str
    url: str


STEAM_GAMES = (
    ("Marvel’s Spider-Man Remastered", "Open-world action", 1817070),
    ("Marvel’s Spider-Man: Miles Morales", "Open-world action", 1817190),
    ("Marvel’s Spider-Man 2", "Open-world action", 2651280),
    ("Battlefield 6", "Multiplayer shooter", 2807960),
    ("Battlefield 2042", "Multiplayer shooter", 1517290),
    ("PUBG: BATTLEGROUNDS", "Battle royale", 578080),
    ("Apex Legends", "Battle royale", 1172470),
    ("Counter-Strike 2", "Tactical shooter", 730),
    ("Grand Theft Auto V Enhanced", "Open-world action", 3240220),
    ("Red Dead Redemption 2", "Open-world adventure", 1174180),
    ("Forza Horizon 5", "Open-world racing", 1551360),
    ("Destiny 2", "Online action", 1085660),
    ("Warhammer 40,000: Space Marine 2", "Action", 2183900),
    ("HELLDIVERS 2", "Co-op action", 553850),
    ("ELDEN RING", "Action RPG", 1245620),
    ("Far Cry 6", "Open-world action", 2369390),
    ("Black Myth: Wukong", "Action RPG", 2358720),
)
GAMES = tuple(
    GameListing(name, genre, "Steam", f"https://store.steampowered.com/app/{app_id}/")
    for name, genre, app_id in STEAM_GAMES
) + (
    GameListing(
        "Fortnite", "Battle royale", "Epic Games Launcher", "https://store.epicgames.com/en-US/p/fortnite"
    ),
)


CATALOG = {
    "Artificial Intelligence": {
        "Ollama": "Ollama.Ollama",
        "LM Studio": "ElementLabs.LMStudio",
        "Jan": "Jan.Jan",
    },
    "Game Launchers": {
        "Steam": "Valve.Steam",
        "Epic Games Launcher": "EpicGames.EpicGamesLauncher",
        "GOG GALAXY": "GOG.Galaxy",
        "Xbox": "9MV0B5HZVK9Z",
        "EA app": "ElectronicArts.EADesktop",
        "Ubisoft Connect": "Ubisoft.Connect",
        "Heroic Games Launcher": "HeroicGamesLauncher.HeroicGamesLauncher",
        "Minecraft Launcher": "Mojang.MinecraftLauncher",
        "Playnite": "Playnite.Playnite",
        "Wargaming Game Center": "Wargaming.GameCenter",
    },
    "Games": {
        "Roblox": "Roblox.Roblox",
        "OpenTTD": "OpenTTD.OpenTTD",
        "SuperTuxKart": "SuperTuxKart.SuperTuxKart",
        "The Battle for Wesnoth": "Wesnoth.BattleForWesnoth",
        "Endless Sky": "EndlessSky.EndlessSky",
        "0 A.D.": "WildfireGames.0AD",
        "Xonotic": "Xonotic.Xonotic",
        "OpenRA": "OpenRA.OpenRA",
        "Warzone 2100": "Warzone2100Project.Warzone2100",
        "ScummVM": "ScummVM.ScummVM",
    },
    "Video Editing": {
        "OpenShot Video Editor": "OpenShot.OpenShot",
        "CapCut": "ByteDance.CapCut",
        "OBS Studio": "OBSProject.OBSStudio",
        "Kdenlive": "KDE.Kdenlive",
        "Shotcut": "Meltytech.Shotcut",
        "HandBrake": "HandBrake.HandBrake",
    },
    "Photo Editing": {
        "GIMP": "GIMP.GIMP.3",
        "RawTherapee": "RawTherapee.RawTherapee",
        "ShareX": "ShareX.ShareX",
    },
    "Graphic Design": {
        "Blender": "BlenderFoundation.Blender",
        "Krita": "KDE.Krita",
        "Inkscape": "Inkscape.Inkscape",
        "Figma": "Figma.Figma",
        "FreeCAD": "FreeCAD.FreeCAD",
        "KiCad": "KiCad.KiCad",
        "PrusaSlicer": "Prusa3D.PrusaSlicer",
        "Bambu Studio": "Bambulab.Bambustudio",
        "Ultimaker Cura": "Ultimaker.Cura",
    },
    "Programming": {
        "Visual Studio Code": "Microsoft.VisualStudioCode",
        "Cursor": "Anysphere.Cursor",
        "Godot": "GodotEngine.GodotEngine",
        "Python 3.13": "Python.Python.3.13",
        "GDevelop": "GDevelop.GDevelop",
        "Kate": "KDE.Kate",
        "PyCharm Community": "JetBrains.PyCharm.Community",
    },
    "Productivity": {
        "LibreOffice": "TheDocumentFoundation.LibreOffice",
        "Notion": "Notion.Notion",
        "Obsidian": "Obsidian.Obsidian",
        "Thunderbird": "Mozilla.Thunderbird",
        "Slack": "SlackTechnologies.Slack",
        "Zoom": "Zoom.Zoom",
        "Microsoft Teams": "Microsoft.Teams",
        "SumatraPDF": "SumatraPDF.SumatraPDF",
        "GnuCash": "GnuCash.GnuCash",
        "Power Automate Desktop": "Microsoft.PowerAutomateDesktop",
    },
    "Entertainment": {
        "VLC": "VideoLAN.VLC",
        "Audacity": "Audacity.Audacity",
        "Spotify": "Spotify.Spotify",
        "Discord": "Discord.Discord",
        "Stellarium": "Stellarium.Stellarium",
    },
    "Browsers": {
        "Firefox": "Mozilla.Firefox",
        "Google Chrome": "Google.Chrome",
        "Brave": "Brave.Brave",
        "Vivaldi": "Vivaldi.Vivaldi",
        "Firefox Developer Edition": "Mozilla.Firefox.DeveloperEdition",
    },
    "Utilities": {
        "FFmpeg": "Gyan.FFmpeg",
        "7-Zip": "7zip.7zip",
        "PowerToys": "Microsoft.PowerToys",
        "qBittorrent": "qBittorrent.qBittorrent",
        "Bitwarden": "Bitwarden.Bitwarden",
        "KeePassXC": "KeePassXCTeam.KeePassXC",
        "Tailscale": "Tailscale.Tailscale",
        "WizTree": "AntibodySoftware.WizTree",
        "Transmission": "Transmission.Transmission",
        "Sysinternals Suite": "Microsoft.Sysinternals.Suite",
    },
    "Development Tools": {
        "Git": "Git.Git",
        "Node.js LTS": "OpenJS.NodeJS.LTS",
        "GitHub Desktop": "GitHub.GitHubDesktop",
        "JetBrains Toolbox": "JetBrains.Toolbox",
        "Docker Desktop": "Docker.DockerDesktop",
        "Windows Terminal": "Microsoft.WindowsTerminal",
        "PowerShell": "Microsoft.PowerShell",
        ".NET 10 SDK": "Microsoft.DotNet.SDK.10",
    },
}

STORE_IDS = frozenset({"9MV0B5HZVK9Z"})

# Local artwork named after the icon slug in desktop/assets/app-logos/NOTICE.md.
LOGOS = {
    "Ollama.Ollama": "ollama",
    "ElementLabs.LMStudio": "lmstudio",
    "Valve.Steam": "steam",
    "EpicGames.EpicGamesLauncher": "epicgames",
    "GOG.Galaxy": "gogdotcom",
    "ElectronicArts.EADesktop": "ea",
    "Ubisoft.Connect": "ubisoft",
    "Roblox.Roblox": "roblox",
    "OBSProject.OBSStudio": "obsstudio",
    "KDE.Kdenlive": "kdenlive",
    "GIMP.GIMP.3": "gimp",
    "BlenderFoundation.Blender": "blender",
    "KDE.Krita": "krita",
    "Inkscape.Inkscape": "inkscape",
    "Microsoft.VisualStudioCode": "vscode",
    "Anysphere.Cursor": "cursor",
    "GodotEngine.GodotEngine": "godotengine",
    "Python.Python.3.13": "python",
    "TheDocumentFoundation.LibreOffice": "libreoffice",
    "Notion.Notion": "notion",
    "Obsidian.Obsidian": "obsidian",
    "Mozilla.Thunderbird": "thunderbird",
    "Zoom.Zoom": "zoom",
    "VideoLAN.VLC": "vlcmediaplayer",
    "Audacity.Audacity": "audacity",
    "Spotify.Spotify": "spotify",
    "Discord.Discord": "discord",
    "Mozilla.Firefox": "firefoxbrowser",
    "Google.Chrome": "googlechrome",
    "Brave.Brave": "brave",
    "Vivaldi.Vivaldi": "vivaldi",
    "7zip.7zip": "7zip",
    "qBittorrent.qBittorrent": "qbittorrent",
    "Git.Git": "git",
    "OpenJS.NodeJS.LTS": "nodedotjs",
    "GitHub.GitHubDesktop": "github",
    "JetBrains.Toolbox": "jetbrains",
    "Docker.DockerDesktop": "docker",
    "Figma.Figma": "figma",
    "FreeCAD.FreeCAD": "freecad",
    "KiCad.KiCad": "kicad",
    "Bitwarden.Bitwarden": "bitwarden",
    "KeePassXCTeam.KeePassXC": "keepassxc",
    "Tailscale.Tailscale": "tailscale",
    "Transmission.Transmission": "transmission",
    "Mozilla.Firefox.DeveloperEdition": "firefoxbrowser",
}
