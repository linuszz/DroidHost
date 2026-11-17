# DroidHost's NixOS machine: a container system with the desktop baked in.
#
# The app runs this under proot, not under a hypervisor: no systemd, no bootloader, no network
# daemons. Everything a session needs is in the closure, so the machine never installs a desktop
# or a driver at runtime the way the apt-based machines do. The activation script below adds the
# FHS paths the app's injected scripts hardcode: Debian-shaped shebangs (#!/bin/bash,
# #!/usr/bin/python3) and /usr/bin/kwin_wayland. NixOS itself only creates /bin/sh and
# /usr/bin/env, and keeps every program under /nix/store.
{ config, pkgs, lib, ... }:

{
  boot.isContainer = true;
  boot.loader.initScript.enable = true;
  boot.tmp.useTmpfs = false;

  nixpkgs.hostPlatform = "aarch64-linux";
  system.stateVersion = "26.05";

  networking.hostName = "droidhost";
  networking.useDHCP = false;
  networking.networkmanager.enable = false;
  services.resolved.enable = false;
  time.timeZone = "UTC";

  i18n.defaultLocale = "C.UTF-8";

  users.mutableUsers = false;
  # No login surface under proot: the app writes passwd/group and delegates root through its own
  # service, so the declarative root account needs no password.
  users.allowNoPasswordLogin = true;
  users.users.root = {
    home = "/root";
    createHome = true;
    shell = pkgs.bashInteractive;
  };

  services.desktopManager.plasma6.enable = true;
  services.displayManager.sddm.enable = false;
  programs.xwayland.enable = true;
  services.dbus.enable = true;
  # The app brings its own audio path; a pipewire module would only put read-only /etc entries
  # where droiddeck-desktop writes its own.
  services.pipewire.enable = false;
  services.pulseaudio.enable = false;
  hardware.bluetooth.enable = false;
  services.printing.enable = false;
  # H.8: the guest's sudo is the app's own wrapper, delegating to the app's root service. A
  # setuid sudo cannot work under proot and has nothing to delegate to here.
  security.sudo.enable = false;

  hardware.graphics.enable = true;

  fonts.fontDir.enable = true;
  fonts.packages = with pkgs; [
    noto-fonts
    noto-fonts-cjk-sans
    noto-fonts-emoji
  ];

  environment.systemPackages = with pkgs; [
    bashInteractive
    coreutils
    findutils
    gnused
    gnugrep
    gawk
    gnutar
    gzip
    xz
    zstd
    procps
    iproute2
    iputils
    curl
    wget
    less
    nano
    file
    which
    diffutils
    patchelf
    htop
    python3
    nix
    kdePackages.kconfig
    kdePackages.konsole
    kdePackages.kwin
    kdePackages.plasma-workspace
    dbus
    xdg-user-dirs
    shared-mime-info
    glib
    gtk3
    mesa-demos
    vulkan-tools
  ];

  nix.settings = {
    experimental-features = [ "nix-command" "flakes" ];
    # proot cannot offer the namespaces a sandbox build wants.
    sandbox = false;
    auto-optimise-store = false;
    substituters = [
      "https://mirrors.tuna.tsinghua.edu.cn/nix-channels/store"
      "https://cache.nixos.org/"
    ];
    trusted-public-keys = [
      "cache.nixos.org-1:6NCHdD59X431o0gWypbMrAURkbJ16ZPMQFGspcDShjY="
    ];
  };
  nix.gc.automatic = false;
  nix.optimise.automatic = false;

  documentation.enable = false;
  documentation.man.enable = false;
  documentation.info.enable = false;
  documentation.nixos.enable = false;

  # The FHS shim: symlinks into the store for the paths the app's scripts name outright. They
  # point at the wrapped Qt binaries, so kwin_wayland finds its plugins the way NixOS intends.
  system.activationScripts.droiddeckFhs = {
    deps = [ "etc" "users" ];
    text = ''
      mkdir -p /bin /usr/bin
      ln -sfn ${lib.getBin pkgs.bashInteractive}/bin/bash /bin/bash
      ln -sfn ${lib.getBin pkgs.bashInteractive}/bin/bash /usr/bin/bash
      ln -sfn ${lib.getBin pkgs.bashInteractive}/bin/sh /bin/sh
      ln -sfn ${lib.getBin pkgs.coreutils}/bin/env /usr/bin/env
      ln -sfn ${lib.getBin pkgs.python3}/bin/python3 /usr/bin/python3
      ln -sfn ${lib.getBin pkgs.kdePackages.kwin}/bin/kwin_wayland /usr/bin/kwin_wayland
      ln -sfn ${lib.getBin pkgs.kdePackages.kconfig}/bin/kwriteconfig6 /usr/bin/kwriteconfig6
      ln -sfn ${lib.getBin pkgs.kdePackages.kconfig}/bin/kreadconfig6 /usr/bin/kreadconfig6
      ln -sfn ${lib.getBin pkgs.dbus}/bin/dbus-run-session /usr/bin/dbus-run-session
      ln -sfn ${lib.getBin pkgs.kdePackages.plasma-workspace}/bin/startplasma-wayland /usr/bin/startplasma-wayland
      ln -sfn ${lib.getBin pkgs.xdg-user-dirs}/bin/xdg-user-dir /usr/bin/xdg-user-dir
      ln -sfn ${lib.getBin pkgs.shared-mime-info}/bin/update-mime-database /usr/bin/update-mime-database
      ln -sfn ${lib.getBin pkgs.gtk3}/bin/gtk-update-icon-cache /usr/bin/gtk-update-icon-cache
      ln -sfn ${lib.getBin pkgs.glib}/bin/glib-compile-schemas /usr/bin/glib-compile-schemas
      ln -sfn ${lib.getBin pkgs.kdePackages.konsole}/bin/konsole /usr/bin/konsole

      # The app writes these through; NixOS leaves them as read-only links into the store.
      for f in resolv.conf hosts machine-id; do
        if [ -L /etc/$f ]; then
          cp --remove-destination "$(readlink -f /etc/$f)" /etc/$f
        fi
      done
      if [ ! -s /etc/machine-id ]; then
        head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n' > /etc/machine-id
      fi
    '';
  };
}
