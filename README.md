# Capitol TV — régie multi-flux pour les Jeux

Site de react « Capitol TV » pour l'event Hunger Games RP. Il tourne entièrement sur **Cloudflare Workers**, avec une offre gratuite qui suffit pour un event :

- **Connexion Discord obligatoire.** Personne n'accède au site sans compte Discord, et tu peux le réserver aux membres d'un serveur.
- **Temps réel.** Annonces, alertes, présence et équipe passent par un *Durable Object* et des WebSockets.
- **Différé configurable.** La régie règle le délai, et les annonces du site restent synchronisées avec les streams retardés.
- **Pages Tributs et Sponsors**, avec fermeture des flux par horaires pour les joueurs encore en jeu.
- **Présence.** Le compteur « en ligne » est affiché en permanence, et on voit qui regarde quel tribut.
- **Page Équipe** avec trois niveaux d'accès : Régie, Modération, Équipe.

```
capitol-tv/
├── wrangler.jsonc     ← configuration Cloudflare (à remplir)
├── package.json
├── src/worker.js      ← serveur : connexion Discord, temps réel, Twitch
└── public/
    ├── index.html     ← le site
    ├── login.html     ← page de connexion
    └── logo.png       ← ton logo (à ajouter)
```

---

## 1. Préparer

1. Installe **Node.js 20 ou plus** (nodejs.org).
2. Ouvre un terminal dans le dossier `capitol-tv`, puis lance :
   ```
   npm install
   npx wrangler login
   ```
   Le terminal ouvre ton navigateur pour te connecter à ton compte Cloudflare. Crée-en un, gratuitement, si besoin.
3. Place ton image de logo dans `public/logo.png`.

## 2. Créer l'application Discord

1. Va sur **discord.com/developers/applications**, clique sur *New Application* et nomme-la « Capitol TV ».
2. Dans l'onglet **OAuth2** :
   - Copie le **Client ID**.
   - Clique sur *Reset Secret* et copie le **Client Secret**. Garde-le pour toi.
   - Dans **Redirects**, ajoute l'adresse de rappel de ton site. La première fois, ce sera :
     `https://capitol-tv.<ton-sous-domaine>.workers.dev/auth/callback`
     Tu connaîtras l'adresse exacte après le premier déploiement (étape 4). Tu pourras revenir l'ajouter à ce moment-là.

## 3. Remplir la configuration

Dans `wrangler.jsonc`, section `vars` :

| Variable | Valeur |
|---|---|
| `DISCORD_CLIENT_ID` | le Client ID Discord |
| `OWNER_DISCORD_IDS` | **ton ID Discord** (Discord › Paramètres › Avancés › Mode développeur, puis clic droit sur ton profil › *Copier l'identifiant*). Pour plusieurs organisateurs, sépare les ID par des virgules. Ces comptes sont toujours en régie. |
| `DISCORD_GUILD_ID` | *facultatif* : ID du serveur Discord de l'event. Si tu le remplis, seuls ses membres peuvent entrer. |
| `TWITCH_CLIENT_ID` | *facultatif*, voir plus bas |

Ensuite, enregistre les secrets. Chaque commande te demande de coller la valeur :

```
npx wrangler secret put DISCORD_CLIENT_SECRET
npx wrangler secret put SESSION_SECRET
```

Pour `SESSION_SECRET`, colle une longue phrase aléatoire, d'au moins 32 caractères. Elle sert à signer les sessions.

## 4. Mettre en ligne

```
npx wrangler deploy
```

Wrangler affiche l'adresse du site. Ajoute `https://…workers.dev/auth/callback` dans les *Redirects* Discord (étape 2), puis ouvre le site. Tu arrives sur la page de connexion Discord. Une fois connecté avec ton compte, tu es en **Régie**.

Pour mettre à jour le site plus tard, refais simplement `npx wrangler deploy`.

> **Nom de domaine perso**, par exemple `capitol.tondomaine.fr` : dans le tableau de bord Cloudflare, va dans Workers › capitol-tv › *Settings › Domains & Routes*. Pense à ajouter aussi la nouvelle adresse de rappel dans Discord.

---

## Le différé de diffusion

Tu le règles dans **Régie › Jeux & différé**, de 30 s à 15 min.

**Ce que fait le site :**
- Pour les spectateurs, il retient tout ce qu'il annonce pendant le délai choisi : coups de canon, parachutes, flashs, hommages, journal, statuts « blessé / tombé », classement. Chaque annonce apparaît au moment où la scène passe dans les streams.
- Le serveur garde ces informations jusqu'à ce que le délai soit écoulé. Elles n'arrivent jamais en avance dans le navigateur des spectateurs.
- Le chat Twitch peut être masqué pendant le différé, pour éviter les spoils. C'est une option.

**Ce que le site ne peut pas faire :** retarder une vidéo Twitch. **Chaque streamer doit régler le même délai** de son côté :
- sur Twitch : *Gestionnaire de stream › Délai du stream* (jusqu'à 15 min, réservé aux partenaires) ;
- ou dans OBS : *Paramètres › Avancé › Délai de diffusion* (disponible pour tout le monde).

**Mode présentateur :** clique sur ton avatar, en haut à droite. Tu vois alors l'antenne avec le différé, comme les spectateurs. C'est idéal pour réagir en live sans te spoiler. Les alertes restent visibles, mais l'édition est mise en pause.

## Les tributs

- **Page Tributs** (en haut, ou touche `U`) : tous les participants, avec leur fiche RP et leur statut **En vie / Blessé / Disparu / Décédé**. On peut filtrer par statut et par district, et chercher par nom. Un clic sur un tribut ouvre sa fiche complète, avec les boutons Regarder, Signaler et Sponsoriser.
- **Ajouter des tributs** : *Régie › Tributs*. Tu peux faire un ajout rapide ou un import en masse : une ligne par tribut, au format `pseudo ; nom ; district ; n° ; alliance ; ID Discord`. Le bouton **Fiche** de chaque ligne permet ensuite de remplir :
  - les infos RP : âge, arme ou atout, compétences, histoire, portrait (lien https vers une image) ;
  - le compte Discord du joueur ;
  - un lien sponsor dédié.
- **Changer un statut** pendant le live : boutons rapides sous chaque flux (Blessé, Disparu, Tombé), ou dans la fiche. Avec le différé, les spectateurs voient chaque changement de statut seulement quand la scène passe dans les streams.

## Fermer les flux aux tributs en jeu

Ce réglage se trouve dans *Régie › Accès des tributs*.

1. Lie chaque tribut au **compte Discord de son joueur**, dans sa fiche. Si le joueur est connecté au site, son compte apparaît dans la liste proposée.
2. Choisis un mode :
   - **Selon les créneaux** : par exemple tous les jours de 21:00 à 02:00. Un créneau peut passer minuit.
   - **Fermé maintenant** : fermeture manuelle immédiate.
3. Pendant un créneau, les joueurs dont le tribut est encore en jeu (en vie, blessé ou disparu) voient l'écran « Retransmission fermée aux tributs » avec l'heure de réouverture.
   - Le serveur ne leur envoie plus aucun pseudo Twitch, ni statut live, ni compteur de spectateurs.
   - Ils gardent l'accès aux pages Tributs, Sponsors et Équipe.
   - Un tribut **décédé** retrouve l'accès automatiquement.
   - La régie et la modération ne sont jamais bloquées.

> Limite : Capitol TV ferme ses propres lecteurs, mais ne peut pas empêcher un joueur d'aller directement sur twitch.tv. Ça relève du règlement de l'event.

## Sponsors

Les dons ne passent **pas** par Capitol TV. Dans *Régie › Sponsors*, tu règles :
- le **lien de la plateforme de dons** de l'association et le texte du bouton. Le lien peut contenir `{login}`, `{name}`, `{num}` ou `{district}`, remplacés par les infos du tribut choisi ;
- un texte de présentation ;
- les **partenaires officiels** (nom, lien, logo).

La page **Sponsors** affiche ensuite un bouton « Sponsoriser » pour chaque tribut encore en lice, ainsi que les derniers parachutes annoncés. Les parachutes restent de simples annonces à l'antenne : aucun montant n'est géré par le site.

## Cagnotte pour l'association

Dans *Régie › Cagnotte* :
- règle l'**objectif** et la devise ;
- **ajoute ou retire** des montants (boutons rapides +5, +10, +20, +50, +100, +500, ou montant libre avec une note).

Chaque mouvement est enregistré avec le nom de la personne et peut être annulé. Le total est calculé par le serveur.

Le public voit la barre de progression dans l'en-tête, le bandeau et la page Sponsors, avec une petite animation « +50 € ! » à chaque ajout. Les dons eux-mêmes se font sur la **page de dons dédiée** (lien réglé dans *Régie › Sponsors*), puis sont reversés à l'association.

## Consultation sans compte

Dans *Régie › Accès & bannis*, le bouton **Ouvrir à tout le monde** permet à n'importe qui de regarder Capitol TV sans compte Discord. Les invités :
- peuvent regarder, zapper et voir les pages Tributs, Sponsors et Équipe ;
- ne peuvent pas envoyer de signalement : un bouton « Se connecter » leur est proposé ;
- sont comptés dans « en ligne », sans être listés un par un.

**Rendre la connexion obligatoire** renvoie immédiatement les invités vers la page de connexion.

## Caméras en jeu : le mod Minecraft (Fabric 1.21.8)

Le dossier `mod/` contient un mod Fabric pour les caméramans. Le fichier `.jar` est compilé automatiquement par GitHub Actions (onglet **Actions › Mod caméra › Artifacts**). Il faut aussi **Fabric API** dans le dossier `mods`.

**Mise en place**
1. Page **Équipe** › modifier un membre › **Caméra en jeu** : *Caméraman* ou *Responsable caméra*.
2. Chaque caméra ouvre **Mon compte › Mod caméra › Générer mon jeton** sur le site, puis en jeu : touche **K** › **Réglages** › **Coller** › **Connecter**.
3. Dans la fiche de chaque tribut, renseigner son **pseudo Minecraft** (sinon il n'apparaît ni sur la carte ni dans les actes).

**Dans le jeu (touche K)**
- **Actions** : les signalements en cours, avec « écran dans 1:42 » (le différé) ; boutons TP, *Je filme*, *Je prends*, *Traité*.
- **Annonces** : les actes annonciateurs — attaques, dégâts **avec la cause**, morts, effets, totems, points de vie bas, rencontres entre tributs, annonces spéciales — filtrables par type et par tribut.
- **Carte** : positions des tributs et des caméras, zoom à la molette, déplacement à la souris, **TP en un clic** (vers un tribut, une caméra ou un point).
- **Signaler** : tribut, type, urgent, « je filme cette action », détails. La touche **J** envoie un signal rapide sur le tribut suivi (ou le plus proche).
- **Comms** : Général, Caméras, messages directs, réponses rapides ; « Buzz » pour la régie et le responsable caméra.
- **Équipe** : état de chaque caméra (actif, en rec, pause, inactif), qui suit qui, TP vers une caméra ; le responsable peut assigner une caméra à un tribut.
- **Demandes** : demandes de couverture (prendre, terminer, libérer).
- **Réglages** : état, notifications (toutes / supérieurs seulement / aucune), sons, bulles, encart HUD et sa position, envoi de ce que je vois.

**Téléportation** : en mode spectateur, le TP vers un joueur passe par la téléportation vanilla (aucun droit requis). Sinon, le mod serveur s'en charge (spectateurs, opérateurs ou pseudos listés dans `camNames`). Sans mod serveur, le mod envoie `/tp` (droits requis).

**Mod côté serveur (recommandé)** : le même `.jar` installé sur le serveur de l'event envoie la position de *tous* les tributs, les dégâts avec leur cause, les morts et les effets. Régie › **Carte & caméras** › *Afficher et copier* la clé, à coller dans `config/capitoltv-server.json` (`"key"`), puis `/capitol reload`. Commandes : `/capitol status`, `/capitol annonce <texte>` (annonce spéciale envoyée à toutes les caméras). Sans mod serveur, chaque caméra remonte ce qu'elle voit autour d'elle (les doublons sont fusionnés).

**Sur le site** (équipe uniquement) : onglets **Comms** et **Terrain** (caméras, demandes, actes, annonce spéciale) dans le panneau latéral, et compte à rebours d'antenne sur chaque alerte.

**Carte du site** : onglet **Carte** pour l'équipe (positions en direct). Régie › **Carte & caméras** › *Ouvrir la carte* puis *Rendre publique* / *Verrouiller : équipe seulement*. Le public voit les positions **avec le différé**, et les comptes des tributs encore en vie n'y ont jamais accès.

## Vidéo : POV des joueurs et caméras → régie → OBS

**Différé partagé** : le différé est fait par le site et il est commun à tous, même pour une page ouverte ou rechargée en retard. Un navigateur « enregistreur » enregistre les POV de l'antenne par morceaux de 3 secondes et les dépose sur le serveur (bucket des clips, dossier `dvr/`, vidé automatiquement). C'est la page Direct d'OBS de préférence, sinon une page Direct de l'équipe, sinon la régie. Chaque page Direct relit exactement le même passé et l'historique des plans. Une page qui arrive en retard affiche donc tout de suite l'image d'il y a un différé. Il faut seulement qu'une page Direct (OBS) soit ouverte avant la prise à l'antenne. L'ordinateur d'OBS envoie environ 3,5 Mbit/s par POV à l'antenne. Les POV en repli Twitch ne peuvent pas être retardées par le site.

**Chemin de l'image** : chaque joueur et chaque caméra envoie son écran (le monde seulement, sans HUD ni chat) au serveur vidéo de Cloudflare (Realtime). La régie compose les plans sur la page **Réalisation**, la page **Direct** les affiche, et le streamer la capture dans OBS (source *Navigateur*, lien donné par le bouton **Lien OBS**). Le public du site ne voit que les POV Twitch.

**Joueurs : aucune manipulation.** Avec le mod installé sur le serveur de l'event (clé serveur renseignée), chaque joueur dont le **pseudo Minecraft** est rempli dans sa fiche reçoit automatiquement sa clé. À la première connexion, une fenêtre lui demande son accord (J'accepte / Je refuse). La qualité n'est pas à choisir : toujours le maximum autorisé par la régie, baissé automatiquement si le jeu ralentit. Ensuite, touche **K** ouvre le menu (bouton « Ma POV » pour changer d'avis).

**Coût pour les joueurs** : la carte graphique réduit l'image et la recopie en arrière-plan, sans bloquer le jeu ; l'encodage vidéo se fait hors du fil principal.

**Régie** : moniteurs Aperçu et Antenne en image réelle (vidéo + habillage), son activable, modifs *immédiates* ou *manuelles* (bouton **Appliquer**, touche A, et **Simuler les modifs**), 12 dispositions dont la **Mosaïque** (n POV choisis par pseudo, apparition une par une).

**Lien OBS sans compte** : `npx wrangler secret put OVERLAY_KEY` (une longue phrase aléatoire), puis Réalisation › **Lien OBS**.

## Site de secours (2e compte Cloudflare)

Si les 1 000 Go vidéo gratuits partent trop vite, un clic (Réglages › **Secours**) bascule tout le monde vers un 2e site : site, mods des joueurs et des caméras, mod serveur, OBS. Ceux qui étaient hors ligne basculent à leur prochaine connexion, même des jours plus tard (l'ancien site les redirige). Personne n'a à se reconnecter.

**À préparer une fois, avant l'event :**
1. Créer un 2e compte Cloudflare, puis `npx wrangler login` avec ce compte.
2. `npx wrangler r2 bucket create capitol-tv-clips` et le namespace KV des médias (remplacer son id dans une copie de `wrangler.jsonc`), puis `npx wrangler deploy`.
3. Secrets **identiques** au site principal : `SESSION_SECRET`, `DISCORD_CLIENT_SECRET`, `OVERLAY_KEY` ; et ceux de son propre serveur vidéo : `RTC_APP_ID`, `RTC_APP_SECRET` (Realtime › SFU du 2e compte).
4. Discord › OAuth2 › Redirects : ajouter `https://…2e-site…/auth/callback`.
5. Sur le site principal : Réglages › Secours › coller l'adresse du 2e site › **Tester la liaison**.

Le jour J : **Basculer tout le monde vers ce site**. Pour revenir, même bouton depuis le site de secours avec l'adresse du site principal. Les médias et clips sont recopiés à la demande depuis l'ancien site.

## Réutiliser le site pour un autre event

1. *Fork* du dépôt, puis étapes 1 à 4 ci-dessus (Discord, `wrangler.jsonc`, `npx wrangler deploy`).
2. Vidéo : crée une application Cloudflare Realtime (tableau de bord › Realtime › SFU) et enregistre `RTC_APP_ID` et `RTC_APP_SECRET` avec `npx wrangler secret put`. Crée le bucket des clips : `npx wrangler r2 bucket create capitol-tv-clips`.
3. Apparence : Réglages › **Apparence** (nom de l'émission, couleurs), logo dans `public/logo.png`, nom par défaut dans `CONFIG` en haut de `public/index.html`.
4. Mod : le même `.jar` côté joueurs et côté serveur ; `url` du site dans `config/capitoltv-server.json` (serveur) et dans les réglages du mod (équipe).

## L'équipe et les accès

- **Page Équipe** : onglet « Équipe » en haut, ou touche `E`. Elle est visible par tout le monde.
- **Ajouter quelqu'un** : ouvre l'onglet **En ligne**, survole la personne et clique sur **+**. Tu peux aussi l'ajouter depuis la page Équipe avec son ID Discord.
- **Niveaux d'accès :**
  - **Régie** : tout gérer.
  - **Modération** : alertes et bannissements.
  - **Équipe** : affichage sur la page Équipe, sans accès à la régie.
- **Bannir** quelqu'un qui abuse des signalements : bouton ⊘, sur l'alerte ou dans l'onglet En ligne. Pour rendre l'accès : *Régie › Accès & bannis*.

## Options

**Statut en direct Twitch** (qui est en live, nombre de spectateurs) :
1. Sur **dev.twitch.tv/console**, enregistre une application (catégorie *Website Integration*, type *Confidentiel*).
2. Mets son **Client ID** dans `TWITCH_CLIENT_ID`, puis lance `npx wrangler secret put TWITCH_CLIENT_SECRET`.
3. Redéploie. Le serveur interroge Twitch chaque minute.

**Overlay OBS** (bandeau et annonces sur fond transparent) :
1. Lance `npx wrangler secret put OVERLAY_KEY` et choisis un mot de passe.
2. Dans OBS, ajoute une source *Navigateur* en 1920×1080 avec l'adresse `https://…/overlay/ton-mot-de-passe`.

## Tester en local

```
npx wrangler dev
```

Pour que la connexion Discord marche en local, ajoute aussi `http://localhost:8787/auth/callback` dans les *Redirects* Discord. Les secrets locaux se mettent dans un fichier `.dev.vars` :

```
DISCORD_CLIENT_SECRET=...
SESSION_SECRET=...
```

## Coûts et limites

- **Vidéo** : Cloudflare facture uniquement ce que le serveur vidéo *envoie* aux écrans (régie, page OBS) ; ce que les joueurs envoient est gratuit. 1 000 Go gratuits par mois, puis 0,05 $ par Go. Le compteur et le budget sont dans Réglages › **Carte & caméras** : au-delà du budget, la régie passe en qualité légère, la sortie OBS reste en pleine qualité.
- Les petites vignettes (grilles, mosaïques, moniteurs de la régie) utilisent automatiquement une version 360p de chaque flux.

- Tout tient dans l'offre gratuite de Cloudflare pour un event de quelques jours. Au-delà d'environ 100 000 requêtes par jour, le forfait payant coûte 5 $/mois.
- Le compteur « en ligne » affiche tout le monde. La liste détaillée est limitée aux 600 premières personnes, pour rester fluide même avec beaucoup de monde.

## Nouveautés de la régie

- **Double écran** : bouton « Double écran » à côté des plans (page Réalisation). La 2e fenêtre est un tableau de bord :
  - le Direct, exactement ce que voient les spectateurs avec le différé, ou l'antenne en temps réel ; l'image se cache ;
  - l'Aperçu, les plans, « À l'antenne », « Hors antenne » et les Paramètres ;
  - le panneau tributs / alertes / comms / terrain / journal à droite ;
  - en bas, des onglets Multiview (clic sur une POV = dans l'Aperçu), Carte et Sondages & pronostics. Dans l'application de bureau, la fenêtre s'ouvre directement en plein écran sur l'autre moniteur.
- **Fond de carte** : en jeu, `/capitol carte <rayon>` (ou le bouton « Relever le terrain ici » de la page Carte) relève le terrain autour de soi : couleurs des blocs, relief et profondeur de l'eau. La carte du site et la mini-carte du Direct l'utilisent, en vue de dessus ou en vue 3D isométrique. À refaire si l'arène change.
- **Musique de fond en ligne** : la recherche du panneau « Musique de fond » cherche aussi des musiques libres de droits (Openverse : Jamendo, Freesound, Wikimedia…). ▶ pour écouter, « Ajouter » l'importe et la met dans la playlist. Le crédit de l'auteur s'affiche en bas du Direct (obligatoire pour les licences CC BY).
- **Notifications** : cloche en haut à droite (équipe) : alertes, messages, interphone, demandes de couverture, morts, captures qui font ramer. Elles arrivent quand la fenêtre est en arrière-plan.
- **Régie en différé + « À venir »** : les moniteurs de la régie (Aperçu, Antenne, multiview) sont en différé, comme le Direct. L'onglet « À venir » du panneau latéral liste en temps réel, dans l'ordre d'arrivée, ce que les caméras signalent : signalements, demandes de couverture, morts. Pour chacun, un compte à rebours indique quand ce sera à l'image, et un bouton met le tribut ou la caméra dans l'aperçu. Les POV signalées sont enregistrées tout de suite, pour être prêtes en différé.
- **Police du titre** : Réglages › Apparence (Unbounded par défaut, Syne, Space Grotesk, Oswald ou Cinzel).
- **Musique par artiste** : `art:nom de l'artiste` dans la recherche de musique (seulement les artistes en licence libre).
- **Panneau latéral partout** : bouton à côté de la cloche (tributs, alertes, comms, terrain…), y compris en régie.

## Bot Twitch

Réglages › Twitch › « Bot Twitch (chat) » : indique la chaîne du stream et active le bot. Les spectateurs votent au sondage avec `!1`, `!2`…, parient avec `!pari 7 200`, et peuvent taper `!points`, `!tributs` et `!aide`. Leurs votes et paris s'ajoutent à ceux du site. Sans compte, le bot lit seulement le chat. Pour qu'il réponde et annonce les sondages, les résultats, les morts (avec le différé) et le vainqueur, il faut l'application Twitch (`TWITCH_CLIENT_ID` + `TWITCH_CLIENT_SECRET`, avec en plus l'URL de redirection `https://<site>/twitchbot/cb`). Clique ensuite sur « Connecter le compte du bot » en étant connecté sur Twitch avec le compte du bot. Mets le bot modérateur de la chaîne.

## Application de bureau

Le dossier `desktop/` contient l'application Electron. GitHub la construit à chaque modification : release « app-bureau », installateur `CapitolTV-installation-*.exe` et version portable. Elle garde la connexion, lance le son et la vidéo sans clic, affiche les notifications Windows et fait clignoter la barre des tâches. Le double écran s'ouvre sur le 2e moniteur. Raccourcis : F11 plein écran, F5 recharger, Ctrl +/- zoom. L'application n'est pas signée : Windows affiche « Windows a protégé votre ordinateur », clique sur « Informations complémentaires » puis « Exécuter quand même ».
